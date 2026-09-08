package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.github.kusoroadeolu.sl.ConcurrentUnrolledSet.Operation;
import static io.github.kusoroadeolu.sl.EliminationNode.NCPU;

/*
* An improved variant of the EF Unrolled Concurrent List. The main issue with the previous version was:
* 1. It tried to add more concurrency to the global list (which is already highly concurrent)
* 2. The list could change under the combining thread (the thread that didn't hold the lock to its current node)
* forcing every thread even valid ones to rescan the whole list for their valid nodes
*
* We fix this by instead introducing concurrency per node (through similar elimination). A thread which holds a lock to a node now becomes the combiner for that node
* Each node keeps an arena of active requests to be combined. A combining thread can only combine a value on a node with 3 conditions
* 1. The pred node hasn't changed since the request was registered
* 2. The combining request's operation matches that of the combining thread
* 3. The request's operation(specifically in add) respects the anchor invariant from the parent unrolled concurrent list class
*
* To prevent threads with values which do not belong to a certain node from spinning aimlessly on the exchange arena (which caused persistent cpu and latency drops), as
* ideally no thread with their value will match with them (in that node), we simply make that thread wait for the lock to that specific node
* rather than trying to hold the lock then waiting in the arena
*
* This simple change, from profiling,
*  shows waiting in the arena had the majority cpu samples
* seems to have cut the cpu samples from ~2k to ~900, which is still a lot but magnitudes better

 * This class maintains the set invariant. I do believe the combining path might incur some overhead and perform worse than its base class
*
* */
/**
 * @author kusoroadeolu
 * */
public class ConcurrentCombiningUnrolledSet<T extends Comparable<T>> implements ConcurrentCollection<T> {

    private final LocalEFNode<T> left;
    private final LocalEFNode<T> right;
    private final int arrayCap;
    private final int minFull;
    private final int maxMerge;
    private final ThreadLocal<LocalArrays<T>> localArrays;
    private static final int MAX_SPINS = 512;
    private static final int SLOT_SPINS = 128;
    private static final int ARENA_LEN = 4;
    private static final int ARENA_MASK = ARENA_LEN - 1;

    public ConcurrentCombiningUnrolledSet() {
        this(64, 16);
    }

    public ConcurrentCombiningUnrolledSet(int arrCap, int minFull) {
        this.left = new SentinelEFNode<>();
        this.right = new SentinelEFNode<>();
        left.lock(); //Visibility guarantees for plain reads under the lock
        try {
            left.next = right; //Visibility guarantees for threads traversing the lock
        }finally {
            left.unlock();
        }

        this.minFull = minFull;
        this.arrayCap = arrCap;
        maxMerge = (int) (0.75 * arrCap);
        localArrays = ThreadLocal.withInitial(LocalArrays::new);

    }

    public boolean add(T value) {
        Objects.requireNonNull(value);
        LocalEFNode<T> l = left;
        LocalEFNode<T> r = right;
        int aCap = arrayCap;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        CombiningRequest<T> ours = null;
        for (;;) {
            findNode(value, l, r, nodes);
            var pred = nodes[0];
            var curr = nodes[1];

            if (curr.loMarked() || pred.lopMarked()) continue;

            if (curr.contains(value)) return false;


            //true, hold the lock(because we are dont actually belong in this node),
            //boolean held;
            // otherwise try or await exchange
            boolean belongsToNode = belongsToNode(r, curr, value);
            boolean held;
            if ((held = pred.tryLock()) || !belongsToNode) {
                if (!held) pred.lock(); //if we didnt hold the lock and wait, try await
                try {
                    if (isNotValid(pred, curr) || curr.containsPlain(value)) return false;

                    if (!belongsToNode) { //Don't scan if this is the right node
                        LocalEFNode<T> n = new LocalEFNode<>(value, aCap);

                        //pred - n - curr
                        n.soArray(0, value);
                        n.increment(1);
                        n.spNext(curr);
                        pred.soNext(n);
                        return true;
                    }

                    Set<T> matchedValues = new HashSet<>();
                    matchedValues.add(value);
                    scanAndMatchAdd(matchedValues, nodes);

                    int matchedSize = matchedValues.size();
                    int size = curr.lpSize();
                    int newSize = size + matchedSize;

                    if (newSize <= aCap) {
                        int i = size;
                        for (T t: matchedValues) {
                            curr.soArray(i++, t);
                        }

                        curr.increment(matchedSize);
                        return true;
                    } else { //Split
                        curr.lock(); //Lock to ensure no one can modify curr.next during the split
                        // So we have a consistent view of curr.next from when we start the split operation
                        try {
                            var succ = curr.lpNext();
                            split(matchedValues ,newSize ,nodes);
                            var n1 = nodes[0];
                            var n2 = nodes[1];

                            curr.soMarked();

                            n1.spNext(n2);
                            n2.spNext(succ);
                            pred.soNext(n1); //Linearization point, makes n1 and n2 visible
                            return true;
                        }finally {
                            curr.unlock();
                        }
                    }

                }finally {
                    pred.unlock();
                }
            } else {
                 //We want to publish then wait
                if (ours == null) ours = new CombiningRequest<>(Operation.ADD,  value);

                if (curr != r && awaitExchange(ours, nodes, curr.arena, (int) Thread.currentThread().threadId())) {
                    Boolean status = awaitStatus(ours);
                    if (status != null) return status;
                }
            }
        }
    }

    boolean belongsToNode(LocalEFNode<T> r, LocalEFNode<T> curr, T value) {
      return  curr != r && value.compareTo(curr.anchor) >= 0;
    }


    public boolean remove(Object o) {
        T t = (T) Objects.requireNonNull(o);
        LocalEFNode<T> l = left;
        LocalEFNode<T> r = right;
        int capacity = arrayCap;
        var la = localArrays.get();
        var nodes = la.nodes();
        CombiningRequest<T> ours = null;

        for (;;) {
            findNode(t, l, r, nodes);
            var pred = nodes[0];
            var curr = nodes[1];

            if (curr.loMarked() || pred.lopMarked()) continue;

            if (!curr.contains(t)) return false;
            boolean belongsToNode = belongsToNode(r, curr, t);

            if (!belongsToNode) return false;

            if (ours == null) ours = new CombiningRequest<>(Operation.REMOVE, t);

            if (pred.tryLock()) {
                try {
                    if (isNotValid(pred, curr) || !curr.containsPlain(t)) return false;
                    int size = curr.lpSize();
                    HashMap<T, CombiningRequest<T>> valuesToBeRemoved = new HashMap<>();
                    valuesToBeRemoved.put(t, ours);
                    scanAndMatchRemove(valuesToBeRemoved, nodes);

                    int removeCount = removeValues(valuesToBeRemoved, curr ,size, capacity);
                    int currSize = size - removeCount;

                    if (currSize > minFull) return true;
                    curr.lock();
                    try {
                        var succ = curr.lpNext();
                        if (currSize == 0) {
                            curr.soMarked();
                            pred.soNext(succ);
                            return true;
                        }

                        if (succ == r) return true;

                        succ.lock(); //Ensure we lock succ to prevent other threads from making structural modifications to its array
                        try {
                            int succSize = succ.size();
                            int total = currSize + succSize;
                            if (total <= maxMerge) { // Merge to fill the lower indices
                                merge(curr, succ, total);
                            } else { //Redistribute so the lower index is not sparse
                                redistribute(curr, succ, capacity ,total);
                            }

                            return true;
                        }finally {
                            succ.unlock();
                        }

                    }finally {
                        curr.unlock();
                    }


                }finally {
                    pred.unlock();
                }
            } else {
                if (curr != r && awaitExchange(ours, nodes, curr.arena, (int) Thread.currentThread().threadId())) {
                    Boolean status = awaitStatus(ours);
                    if (status != null) return status;
                }
            }
        }

    }

    Boolean awaitStatus(CombiningRequest<T> ours) {
        for(;;) {
            var s = ours.loStatus();
            if (s == Status.SUCCESS) return true;
            else if (s == Status.FAIL) return false;
            else if (s == Status.RETRY) {
                ours.spStatus(Status.INIT);
                return null;
            }

            Thread.onSpinWait();
        }
    }

    private void scanAndMatchAdd(Set<T> valuesToAdd, LocalEFNode<T>[] nodes) {
        var pred = nodes[0];
        var curr = nodes[1];
        for (int i = 0; i < ARENA_LEN; ++i) {
            CombiningRequest<T> theirs = curr.arena.getAcquire(i);
            if (theirs != free() && theirs.op() == Operation.ADD && curr.arena.compareAndSet(i, theirs, free())) {
                //Then check if their pred our current pred. Second check should always be true, just there to be safe though
                if (pred == theirs.pred && curr == theirs.curr &&
                        curr.anchor.compareTo(theirs.value) < 0) { //If this is false, notify that they cannot return
                    //We still need the extra comparison check,
                    // in the case a thread fails to acquire a lock on a node where it is meant to create a new node to link to pred
                    //but it fails and leaves its value in the elimination array


                    if (valuesToAdd.add(theirs.value)) theirs.soStatus(Status.SUCCESS); //Notify if they can return true or not
                    else theirs.soStatus(Status.FAIL);
                } else theirs.soStatus(Status.RETRY); //list has changed under you, retry

            }
        }
    }

    private void scanAndMatchRemove(Map<T, CombiningRequest<T>> valuesToRemove, LocalEFNode<T>[] nodes) {
        var pred = nodes[0];
        var curr = nodes[1];
        var arena = curr.arena;
        for (int i = 0; i < ARENA_LEN; ++i) {
            CombiningRequest<T> theirs = arena.getAcquire(i);
            if (theirs != free() && theirs.op() == Operation.REMOVE && arena.compareAndSet(i, theirs, free())) {
                //Then check if their pred our current pred. Second check should always be true, just there to be safe though
                if (pred == theirs.pred && curr == theirs.curr) { //If this is false, notify that they cannot return
                    if (valuesToRemove.containsKey(theirs.value)) {
                        theirs.soStatus(Status.FAIL); //Duplicate remove entry
                        continue;
                    }

                    valuesToRemove.put(theirs.value, theirs);

                } else theirs.soStatus(Status.RETRY); //list has changed under you, retry

            }
        }
    }

    boolean awaitExchange(CombiningRequest<T> ours, LocalEFNode<T>[] nodes, AtomicReferenceArray<CombiningRequest<T>> arena, int start) {
        var curr = nodes[1];

        for (int i = 0, totalSpins = 0; !curr.loMarked() && totalSpins < MAX_SPINS && i < ARENA_LEN; ++i){
            int slot = (start + i) & ARENA_MASK;
            CombiningRequest<T> theirs = arena.getAcquire(slot);
            if (theirs == free()) {
                ours.pred = nodes[0];
                ours.curr = nodes[1]; //Visibility is ensured through by volatile write
                if (arena.compareAndSet(slot, free(), ours)) {
                    int slotSpins = 0;
                    for (;;) {
                        theirs = arena.getAcquire(slot);
                        if (theirs != ours) return true; //Someone has eliminated us
                        else if (slotSpins >= SLOT_SPINS) {
                            if (arena.getAcquire(slot) == ours && arena.compareAndSet(slot, ours, free())) {
                                totalSpins += slotSpins;
                                break;
                            }
                            else return true; //Someone else has eliminated us
                        }

                        slotSpins++;
                        Thread.onSpinWait();
                    }
                }

                Thread.yield(); //yield processor, let other threads make progress before we continue waiting
            }
//            else if (theirs.op() != ours.op() && theirs.value() == ours.value()
//                    && arena.compareAndSet(slot, theirs, free())) {
//                return true;
//            } Want to maintain strict set invaraiants for now, so we have to remove this

        }

        return false; //Failed to match
    }

    static <T extends Comparable<T>>boolean isNotValid(LocalEFNode<T> pred, LocalEFNode<T> curr) {
        return pred.lpMarked() || curr.lpMarked() || pred.lpNext() != curr;
    }

    void split(Set<T> matchedValues, int newSize, LocalEFNode<T>[] nodes) {
        Object[] copy = new Object[newSize];
        int size = nodes[1].lpSize();
        System.arraycopy(nodes[1].array, 0, copy, 0, size);

        int idx = size;

        for (T h : matchedValues) {
            copy[idx++] = h;
        }

        Arrays.sort(copy);
        Object[] arr1 = new Object[arrayCap];
        Object[] arr2 = new Object[arrayCap];

        int half = newSize >>> 1;
        int rem = newSize - half;
        System.arraycopy(copy, 0, arr1, 0, half);
        System.arraycopy(copy, half, arr2, 0, rem);
        var n1 = new LocalEFNode<T>(arr1);
        var n2 = new LocalEFNode<T>(arr2);

        n1.increment(half);
        n2.increment(rem);

        nodes[0] = n1;
        nodes[1] = n2;
    }

    static <T extends Comparable<T>>void merge(LocalEFNode<T> curr, LocalEFNode<T> succ, int totalSize) {
        int j = 0;
        for (int i = curr.size(); i < totalSize; ++i) {
            curr.soArray(i, succ.lpArray(j++));
        }

        succ.soMarked();

        curr.increment(succ.size());
        curr.soNext(succ.lpNext()); //Plain read for succ as we already hold its lock


    }

    static <T extends Comparable<T>> void findEmptyIndexes(int[] indexes, int arrayCap ,LocalEFNode<T> node) {
        int size = indexes.length;
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = node.lpArray(i);
            if (t == null) {
                if (j == size) return;
                indexes[j++] = i;
            }
        }
    }

    public List<T> toList() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        List<T> ls = new ArrayList<>();
        while (curr != r) {
            var arr = curr.array.clone();
            for (int i = 0; i < arrayCap; ++i) {
                T t = (T) arr[i];
                if (t != null) ls.add(t);
            }

            curr = curr.loNext();
        }

        return ls;
    }

    public boolean contains(Object o) {
        T t = (T) Objects.requireNonNull(o);
        var nodes = localArrays.get().nodes();
        LocalEFNode<T> curr;
        LocalEFNode<T> l = left, r = right;

        do {
            findNode(t, l, r ,nodes);
            curr = nodes[1];
        } while (curr.loMarked());

        if (curr == r || curr.anchor.compareTo(t) > 0) return false;

        for (int i = arrayCap - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }

    static <T extends Comparable<T>>void findNode(T t, LocalEFNode<T> left, LocalEFNode<T> right ,LocalEFNode<T>[] nodes) {
        LocalEFNode<T> pred = left;
        LocalEFNode<T> curr = left.loNext();
        while (curr != right) {
            LocalEFNode<T> next = curr.loNext();
            if (next == right || t.compareTo(next.anchor) < 0) break;
            pred = curr;
            curr = next;
        }

        nodes[0] = pred; nodes[1] = curr;
    }


    int removeValues(Map<T, CombiningRequest<T>> valuesToRemove, LocalEFNode<T> curr, int size , int arrayCap) {
        int removed = 0;
        for (int i = 0; size > 0 && i < arrayCap; ++i) {
            var value = curr.lpArray(i);
            CombiningRequest<T> current;
            if (value != null && (current = valuesToRemove.remove(value)) != null) {
                //remove from the map as we will rescan to mark unseen values as failed
                var elem = curr.lpArray(--size);
                curr.soArray(i, elem);
                curr.spArray(size, null);
                current.soStatus(Status.SUCCESS);
                ++removed;
            }
        }

        curr.size = size;

        for (var entry : valuesToRemove.entrySet()) {
            entry.getValue().soStatus(Status.FAIL);
        }

        return removed;
    }

    public List<T> anchorList() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        List<T> ls = new ArrayList<>();
        while (curr != r) {
            ls.add(curr.anchor);
            curr = curr.loNext();
        }

        return ls;
    }

    public Map<T, List<T>> nodeMap() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        var map = new LinkedHashMap<T, List<T>>();
        while (curr != r) {
            List<T> ls = Arrays
                    .stream(curr.array)
                    .map(a -> (T) a)
                    .filter(Objects::nonNull)
                    .toList();

            map.put(curr.anchor, ls);
            curr = curr.loNext();
        }
        return map;
    }

    public String toString() {
        return nodeMap().toString();
    }

    private static final Object FREE = null;


    static <T extends Comparable<T>>void redistribute(LocalEFNode<T> curr, LocalEFNode<T> succ, int capacity ,int totalSize) {
        int succSize = succ.size();
        Object[] sorted = new Object[succSize];
        System.arraycopy(succ.array, 0, sorted, 0, succSize);
        Arrays.sort(sorted);

        int elemPerNode = totalSize >>> 1;
        int elemsForCurr = Math.max(0, succSize - elemPerNode);


        int start = curr.size;
        for (int i = 0; i < elemsForCurr; ++i) {
            curr.soArray(start++, (T) sorted[i]);
        }

        var nodeArray = new Object[capacity];

        int index = 0;
        for (int i = elemsForCurr; i < succSize; ++i) {
            nodeArray[index++] = sorted[i];
        }

        var newNode = new LocalEFNode<>((T) sorted[elemsForCurr], nodeArray);

        succ.soMarked();

        curr.increment(elemsForCurr);
        newNode.increment(succSize - elemsForCurr);

        newNode.spNext(succ.lpNext());
        curr.soNext(newNode);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int size() {
        return 0;
    }

    static class CombiningRequest<T extends Comparable<T>> {
        final ConcurrentUnrolledSet.Operation operation;
        final T value;
        LocalEFNode<T> pred, curr; //Will be backed by ordered write to array index
        volatile Status status;

        public CombiningRequest(ConcurrentUnrolledSet.Operation operation, T value) {
            this.operation = operation;
            this.value = value;
        }

        public void soStatus(Status s) {
            STATUS.setRelease(this, s);
        }

        public void spStatus(Status s) {
            STATUS.set(this, s);
        }

        public Status loStatus() {
            return (Status) STATUS.getAcquire(this);
        }

        public Operation op() {
            return operation;
        }

        public T value() {
            return value;
        }
    }


    enum Status {
        RETRY,
        SUCCESS, //Return true
        FAIL,  //Return false
        INIT
    }


    static class LocalEFNode<T extends Comparable<T>> {
        final AtomicReferenceArray<CombiningRequest<T>> arena;
        public final T anchor;
        public final Object[] array;
        final Lock lock;
        volatile int size;
        volatile boolean marked;
        volatile LocalEFNode<T> next;

        public LocalEFNode(T anchor, int capacity) {
            this.anchor = anchor;
            this.array = new Object[capacity];
            this.lock = new ReentrantLock();
            arena = fillArena();
        }

        public LocalEFNode(T anchor, int capacity, AtomicReferenceArray<CombiningRequest<T>> arena) {
            this.anchor = anchor;
            this.array = new Object[capacity];
            this.lock = new ReentrantLock();
            this.arena = arena;
        }


        public LocalEFNode(Object[] initialArray) {
            this.anchor = (T) initialArray[0];
            this.array = initialArray;
            this.lock = new ReentrantLock();
            arena = fillArena();
        }

        public LocalEFNode(T anchor, Object[] array) {
            this.anchor = anchor;
            this.array = array;
            this.lock = new ReentrantLock();
            arena = fillArena();
        }

        void lock() {
            lock.lock();
        }

        boolean tryLock() {
            return lock.tryLock();
        }

        void unlock() {
            lock.unlock();
        }

        void spArray(int idx, T t) {
            ARRAY.set(array, idx, t);
        }

        void soArray(int idx, T t) {
            ARRAY.setRelease(array, idx, t);
        }

        T loArray(int idx) {
            return (T) ARRAY.getAcquire(array, idx);
        }

        T lpArray(int idx) {
            return (T) ARRAY.get(array, idx);
        }

        void soNext(LocalEFNode<T> node) {
            NEXT.setRelease(this, node);
        }

        LocalEFNode<T> lpNext() {
            return (LocalEFNode<T>) NEXT.get(this);
        }

        boolean loMarked(){
            return (boolean) MARKED.getAcquire(this);
        }

        boolean lopMarked() {
            return (boolean) MARKED.getOpaque(this);
        }

        boolean lpMarked(){
            return (boolean) MARKED.get(this);
        }

        void soMarked(){
            MARKED.setRelease(this, true);
        }

        public LocalEFNode<T> loNext() {
            return (LocalEFNode<T>) NEXT.getAcquire(this);
        }

        void increment(int by) {
            SIZE.getAndAddRelease(this, by);
        }

        void decrement(int by) {
            SIZE.getAndAddRelease(this, -by);
        }

        int size() {
            return (int) SIZE.getAcquire(this);
        }

        boolean contains(T value) {
            if (array == null)  return false;
            for (int i = 0; i < array.length; ++i) {
                var v = loArray(i);
                if (v != null && value.compareTo(v) == 0) {
                    return true;
                }
            }

            return false;
        }

        boolean containsPlain(T value) {
            for (int i = 0; i < array.length; ++i) {
                var v = lpArray(i);
                if (v != null && value.compareTo(v) == 0) {
                    return true;
                }
            }

            return false;
        }


        public void spNext(LocalEFNode<T> node) {
            NEXT.set(this, node);
        }

        static <T extends Comparable<T>> AtomicReferenceArray<CombiningRequest<T>> fillArena() {
            AtomicReferenceArray<CombiningRequest<T>> arena = new AtomicReferenceArray<>(ARENA_LEN);
            for (int i = 0; i < arena.length(); ++i) {
                arena.setRelease(i, free());
            }
            return arena;
        }

        @Override
        public String toString() {
            return
                    "anchor=" + anchor +
                    ", array=" + Arrays.toString(array);
        }

        public int lpSize() {
            return (int) SIZE.get(this);
        }
    }



    static class SentinelEFNode<T extends Comparable<T>> extends LocalEFNode<T> {
        public SentinelEFNode() {
            super(null, 0, fillArena());
        }
    }

    static <T extends Comparable<T>> CombiningRequest<T> free() {
        return  (CombiningRequest<T>) FREE;
    }

    static class LocalArrays<T extends Comparable<T>> {
        //Used for storing pred and curr arrays;
        final LocalEFNode<T>[] nodes; //0 - pred, 1 - curr
        //Used for storing indices to prevent extra traversals to calculate size;


        public LocalArrays() {
            this.nodes = new LocalEFNode[2];
        }

        public LocalEFNode<T>[] nodes() {
            return nodes;
        }

    }

    private static final VarHandle MARKED;
    private static final VarHandle NEXT;
    private static final VarHandle ARRAY;
    private static final VarHandle STATUS;
    private static final VarHandle SIZE;

    static {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
            STATUS = l.findVarHandle(CombiningRequest.class, "status", Status.class);
            SIZE = l.findVarHandle(LocalEFNode.class, "size", int.class);
            MARKED = l.findVarHandle(LocalEFNode.class, "marked", boolean.class);
            NEXT = l.findVarHandle(LocalEFNode.class, "next", LocalEFNode.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}


/*
* Notes
* I'm investigating a massive performance (throughput and latency) drop on this structure
* which occurs at random when running the Zipfian Benchmark on a tight key space at 8 threads
*
* This benchmark measures if contention on locks (due to a tight key space) significantly affects the base
* unrolled list and if the intra node concurrency measures alleviate contention (if it exists)
*
* I got isolated JFR profile data for both a good run and a bad run (when the perf drop occurs)
*
* So far, the method samples for both don't exactly differ in data, a high percentage of cpu samples are packed
* at the await exchange method, which shows a lot of threads wait in the fc arena awaiting combining
*
* Memory samples showed a different picture though
For both the good and the bad run, a lot of memory ~1.2GB was allocated just due to resizing a hashmap. Which is an issue
* since hashmaps are allocated frequently on the write path
*
* However, the bad run showed a lot of memory allocation for creating new nodes
* Which could point to the fact that values are sparsely allocated across nodes, meaning more nodes, less values in nodes and more pointer chases
* or nodes are getting emptied pretty quickly, needing for more nodes to be created or tbh a combination of both
* There is barely any activity on the split, merge, redistribute path for both the base unrolled and this structure (on both runs) which justifies both of my hypothesis
* This could be due to an unfortunate order of values entering the array
*
*
* However, if this was the issue, the base unrolled will be facing this issue; which it doesn't, it has barely no allocation on the write path
* given that it actually doesn't try to increase intra node concurrency
*
* To dig deeper, I checked the GC events
* In the good and bad runs, the GC pause times were pretty similar, however the bad run had ~3x less GCs than the good run
* All GCs happened in the young generation. No GCs occurred in the old generation for both
*
* The bad run had up to ~5x weak references and ~2x phantom references than the good run (~550 & ~25), compared to (~120 & ~10)
*
* The base unrolled list has 0 weak or phantom refs, which does make sense especially since it doesn't allocate any references
* on the write path(until creation, redistribution, split) of a node,
* compared this structure which allocates
* 1. A combining request 2. A hash map(if a thread holds the lock; this is a bit worse especially since resizing hashmaps seems to allocate a lot of memory)
* on every write operation 3. An arena (on node creation)
*
* The gc numbers seem interesting but I think they might be an issue later but not the cause of this specific issue, as we can clearly see that this doesn't occur on the base unrolled
* list. This moves my focus to the combining arena and its logic, specifically the add flow
*
* I think the next plausible step might be to view the structure of
* 1. The base unrolled
* 2. This structure (on a good run)
* 3. This structure (on a bad run)
* after a fork
*
* After viewing the structure of all these (2 runs, 2 structures, to prevent bias)
* I expected to see a degenerate unrolled linked list (for the bad run), however, this wasnt the case
* The unrolled linked list was packed similar to the base and good run
*
* To make this deterministic. This seems to only occur in tight keyspaces when threads are evenly subscribed to their processor count
* and when the number of nodes in the structure >= 3. At a keyspace of 64, this scenario never occurred. I did notice a deep here
* but it seemed pretty natural at 128 this scenario only occurred when the number of nodes in the structure >= 3, given that normally
* this keyspace only housed 2 nodes at 256 this scenario only occured when the number of nodes in the structure >= 3, though normally this
* keyspace houses 3 nodes.
*
* One thing I noticed for bad runs is that during warmup, the numbers start off strong, dip, then never recover. Which might point to that unlucky
* sequence of numbers, not just in the way you'd expect
* I do have a hypothesis that this dip seems to occur when values that belong to a node that doesnt exist yet, so they keep spinning and retrying until they
* actually unlock the lock. I will test this hypothesis though through aux counters
*
*
* Mainly the control experiment will be how does the thrpt scale when the number of threads whose value don't belong to a node before they enter the combining arena.
*
This will be run with those deterministic factors, especially on the 256 keyspace, with other factors intact, as its easier to replicate the issue like this
This is specifically on the "add" path since removes short circuit if we fail to find a node a value belongs to for removal
*
* * Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                             256  LOCAL_EF  thrpt   10  4.815 ± 0.244  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist             256  LOCAL_EF  thrpt   10    ≈ 0          ops/us
LocalEFIsolationBench.fullWrite:nodeExists                  256  LOCAL_EF  thrpt   10  1.832 ± 0.079  ops/us

* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                             256  LOCAL_EF  thrpt   10  0.793 ± 0.135  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist             256  LOCAL_EF  thrpt   10  0.076 ± 0.013  ops/us
LocalEFIsolationBench.fullWrite:nodeExists                  256  LOCAL_EF  thrpt   10  0.886 ± 0.049  ops/us
*
* From the hypothesis run, we can see that my intuition was correct. Thrpt doesnt degrade when and only when threads entering the elim arena
* hold values whose nodes already exists. Once a thread whose value doesnt fit in any particular present node, thrpt degrades rapidly
* This doesn't exactly mean a thread's value will be combined with, however it does significantly increase the chance of combining
*
* On a larger keyspace, this hypothesis proves to be true as well
* * Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                           10000  LOCAL_EF  thrpt   10  1.489 ± 0.206  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist           10000  LOCAL_EF  thrpt   10  0.143 ± 0.020  ops/us
LocalEFIsolationBench.fullWrite:nodeExists                10000  LOCAL_EF  thrpt   10  0.631 ± 0.035  ops/us
*
* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                           10000  LOCAL_EF  thrpt   10  4.337 ± 0.229  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist           10000  LOCAL_EF  thrpt   10    ≈ 0          ops/us
LocalEFIsolationBench.fullWrite:nodeExists                10000  LOCAL_EF  thrpt   10  1.657 ± 0.112  ops/us
*
*
* From the numbers 10% of the values in the elim arena from the run didn't belong in any specific present nodes, hence just wasting cpu cycles in a tight loop in the arena
since they wouldnt get combined. Another issue is that these threads could act as "parasites" per say, as they could prevent valid threads from actually registering
their value in the arena, hence negating the arena's value.
*
* The simplest way to fix this is to force threads whose values won't fit in any current specific node to wait on the lock to the predecessor node, rather than
* wasting cycles while doing no work in the arena. That's what I'll be testing next
*
* Note that this change doesnt exactly get rid of the root issue, cause that can't be done, rather it ensures threads who have no chance of matching
* values in an elim arena don't bother trying, hence getting rid of the two issues I mentioned earlier
*
* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                             256  LOCAL_EF  thrpt   10  2.396 ± 0.092  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist             256  LOCAL_EF  thrpt   10    ≈ 0          ops/us
LocalEFIsolationBench.fullWrite:nodeExists                  256  LOCAL_EF  thrpt   10  0.081 ± 0.013  ops/us
*
* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                             256  LOCAL_EF  thrpt   10  4.705 ± 0.286  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist             256  LOCAL_EF  thrpt   10    ≈ 0          ops/us
LocalEFIsolationBench.fullWrite:nodeExists                  256  LOCAL_EF  thrpt   10  1.726 ± 0.126  ops/us

*
* The lower bound thrpt has improved massively, with a tighter error margin though at the cost of some thrpt.
* is much better than thrpt that degrades poorly under those "unpredictable" factors I mentioned earlier
*
* Also from these numbers its pretty obvious thrpt scales with the factor of a node existing and degrades with the factor
* a node doesnt exist.
*
* The next thing I plan to look at though is that hashmap resize issue, but its probably an issue for another day
*
* */
