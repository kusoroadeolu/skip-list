package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
* Based on the thesis https://utd-ir.tdl.org/server/api/core/bitstreams/ca02e64a-84c8-45c9-9cb2-721ead65df84/content
* This structure is a linked list of unrolled nodes. Basically nodes which rather than storing one piece of data, they instead store arrays of data
* This improves cache locality as all needed data is immediately loaded to memory in the array and
* reduces pointer chasing as the probability of the node we land on containing the data is increased
*
* Invariants:
* The anchor of a node successor must be greater than its predecessor
* All keys in a node are greater than or equal to that node’s anchor key
* Null elements cannot be added to this list
*
*
* Note that there is no guarantee in which the anchor of a node will always exist in the node
* There is also no guarantee that the array in a node is will always be in sorted order as to keep the array in sorted order will penalize write perf
*
* A node's next flag is always protected by its pred node
* Communication across node to indicate a node is deleted is done mainly through a marked flag
* Visibility during splits and merges is done using a set release write to a node's next pointer,
* as a thread traversing through a thread will always have to read a node's next flag to get to its dest
*
* To allow threads holding the lock less, while the compiler can optimize loops,
* on removes we try to pack the lower index regions of the array with values,
* though at the cost of reads starting from the higher most index values to ensure correctness
*
* To prevent modifications on nodes who we're splitting or redistributing their arrays,
* we first copy their arrays before any operation, while this might seem expensive, we are just paying for the cost of a new array object,
* pre-existing objects are not copied
*
* The invariants of this structure might be violated at certain points though it doesn't affect correctness
*  During a remove, when copying a value from a higher index to a lower index, a duplicate value exists in the array at some point
* This however is mitigated as reader threads traverse the array from the right ensuring no duplicate value is seen during traversals
*
* */
/**
 * @author kusoroadeolu
 * */
@SuppressWarnings("unchecked")
public class UnrolledConcurrentList<T extends Comparable<T>> implements ConcurrentCollection<T> {
    private final Node<T> left;
    private final Node<T> right;
    private final ThreadLocal<LocalArrays<T>> localArrays;

    //Capacity of each array per node
    private final int capacity;
    private final int minFull;
    private final int mergeThreshold;

    public UnrolledConcurrentList() {
        this(64, 16);
    }

    public UnrolledConcurrentList(int capacity, int minFull) {
        this.left = new SentinelNode<>();
        this.right = new SentinelNode<>();
        left.lock();
        try {
            left.next = right;
        }finally {
            left.unlock();
        }

        localArrays = ThreadLocal.withInitial(LocalArrays::new);
        this.minFull = minFull;
        this.capacity = capacity;
        mergeThreshold = (int) (0.75 * capacity);
    }


    public boolean add(T t) {
        Objects.requireNonNull(t);
        Node<T> left = this.left;
        Node<T> right = this.right;
        int capacity = this.capacity;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        while (true) {
            if (isPresent(t, left, right ,nodes)) return false;
            var pred = nodes[0];
            var curr = nodes[1];

           if (pred.lopMarked()) continue;

           pred.lock();
            try {
                if (isNotValid(pred, curr)) continue;

                if (curr == right || t.compareTo(curr.anchor) < 0) {
                    Node<T> n = new Node<>(t, capacity);

                    n.increment(1);
                    n.spArray(0, t);
                    n.spNext(curr);
                    pred.soNext(n);
                    return true;
                }

                int valueIndex = findValueIndex(t, curr);

                if (valueIndex != -1) return false; //already exists

                int size = curr.lpSize();

                if (size < capacity) {
                    curr.soArray(size, t); //Linearization point
                    curr.increment(1);
                    return true;
                } else { //Split
                    curr.lock(); //Lock to ensure no one can modify curr.next during the split
                    try {
                        var succ = curr.lpNext();
                        split(capacity ,t ,nodes);
                        var n1 = nodes[0];
                        var n2 = nodes[1];

                        curr.soMarked();

                        n1.spNext(n2);
                        n2.spNext(succ);
                        pred.soNext(n1); //Linearization point
                        return true;
                    }finally {
                        curr.unlock();
                    }
                }

            }finally {
                pred.unlock();
                nodes[0] = null;
                nodes[1] = null; //avoid holding refs
            }
        }
    }

    public boolean remove(Object o) {
        T t = (T) Objects.requireNonNull(o);
        Node<T> l = left;
        Node<T> r = right;
        int capacity = this.capacity;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        while (true) {
            if (!isPresent(t, l, r ,nodes)) return false;
            var pred = nodes[0];
            var curr = nodes[1];

            if (pred.lopMarked()) continue;

            pred.lock();
            try {
                if (isNotValid(pred, curr)) continue;

                int index = findValueIndex(t, curr);
                int size = curr.lpSize();

                if (index == -1) return false;

                removeValueAtIndex(index, size ,curr);

                curr.decrement();

                int currSize = size - 1;

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
                        if (total <= mergeThreshold) { // Merge to fill the lower indices
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
                nodes[0] = null;
                nodes[1] = null;
            }

        }
    }

    public boolean contains(Object o) {
        T t = (T) Objects.requireNonNull(o);
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        Node<T> curr;
        Node<T> l = left, r = right;

        do {
            findNode(t, l, r ,nodes);
            curr = nodes[1];
        } while (curr.loMarked());

        if (curr == r || curr.anchor.compareTo(t) > 0) return false;

        for (int i = capacity - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }

    //Benchmark                                   (keySpaceSize)    (type)   Mode  Cnt        Score        Error  Units
    //ZipfianBenchmark.eightyWriteTwentyRead              100000  UNROLLED  thrpt   20  6020456.620 ± 390577.874  ops/s
    //ZipfianBenchmark.eightyWriteTwentyRead:jfr          100000  UNROLLED  thrpt               NaN                 ---



    static <T extends Comparable<T>> void findEmptyIndexes(int[] indexes, int arrayCap ,Node<T> node) {
        int size = indexes.length;
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = node.lpArray(i);
            if (t == null) {
                if (j == size) return;
                indexes[j++] = i;
            }
        }
    }

    static <T extends Comparable<T>>void split(int arrayCap ,T t ,Node<T>[] nodes) {
        int len = arrayCap + 1;

        Object[] copy = Arrays.copyOf(nodes[1].array, len); //Copy to prevent modifying the initial array
        copy[arrayCap] = t;

        Arrays.sort(copy);
        Object[] arr1 = new Object[arrayCap];
        Object[] arr2 = new Object[arrayCap];

        int half = len / 2;
        int rem = len - half;
        System.arraycopy(copy, 0, arr1, 0, half);
        System.arraycopy(copy, half, arr2, 0, rem);

        var n1 = new Node<T>(arr1);
        var n2 = new Node<T>(arr2);

        n1.increment(half);
        n2.increment(rem);
        nodes[0] = n1;
        nodes[1] = n2;
    }

    static int findNonNullIndex(Object[] arr, int arrayCap ,int index) {
        for (int i = 0; i < arrayCap; ++i) {
            if (i != index && arr[i] != null) return i;
        }

        return -1;
    }

    static <T extends Comparable<T>>void merge(Node<T> curr, Node<T> succ ,int totalSize) {

        int j = 0;
        for (int i = curr.size(); i < totalSize; ++i) {
            curr.soArray(i, succ.lpArray(j++));
        }

        succ.soMarked();

        curr.increment(succ.size());
        curr.soNext(succ.lpNext()); //Plain read for succ as we already hold its lock

    }


    //total size: total number of elems in curr and succ
    static <T extends Comparable<T>>void redistribute(Node<T> curr, Node<T> succ, int capacity ,int totalSize) {
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

        var newNode = new Node<>((T) sorted[elemsForCurr], nodeArray);

        succ.soMarked();

        curr.increment(elemsForCurr);
        newNode.increment(succSize - elemsForCurr);

        newNode.spNext(succ.lpNext());
        curr.soNext(newNode);
    }

    /* 5, 10 = 15
     * Total = currSize + succSize
     * newNodeSize 10 - 7 = 3
     *
     * node = 10 (items)
     * currSize = 5 + 3;
     *
     * */

    static Object[] filterNulls(Object[] array, int size) {
        Object[] copy = new Object[size];
        int idx = 0;
        for (Object o : array) {
            if (o != null) copy[idx++] = o;
        }

        return copy;
    }

    static <T extends Comparable<T>> void removeValueAtIndex(int index, int size , Node<T> curr) {
        int replacement = size - 1;
        if (index < replacement) { //Array is logically empty or we were the last value in the array
            curr.soArray(index, curr.lpArray(replacement)); //Move the value at swapIndex forward first before nulling out
            curr.spArray(replacement, null);
        } else {
            curr.soArray(index, null);
        }

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


    //For benchmarks, not an actual clear method lol
    @Override
    public void clear() {
        left.lock();
        try {
            left.soNext(right);
        }finally {
            left.unlock();
        }
    }

    static <T extends Comparable<T>>boolean isNotValid(Node<T> pred, Node<T> curr) {
        return pred.lpMarked() || curr.lpMarked() || pred.lpNext() != curr;
    }

    static <T extends Comparable<T>>boolean isPresent(T t, Node<T> left, Node<T> right ,Node<T>[] nodes){
        findNode(t, left, right, nodes);
        var curr = nodes[1];

        if (curr == right || curr.loMarked() || curr.anchor.compareTo(t) > 0) return false;

        for (int i = curr.size() - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }
    
    static <T extends Comparable<T>>void findNode(T t, Node<T> left, Node<T> right ,Node<T>[] nodes) {

        Node<T> pred = left;
        Node<T> curr = pred.loNext();
        while (curr != right) {
            Node<T> next = curr.loNext();
            if (next == right || t.compareTo(next.anchor) < 0) break;
            pred = curr;
            curr = next;

        }
        nodes[0] = pred; nodes[1] = curr;
    }

    static <T extends Comparable<T>> int compare(T t, Node<T> other, Node<T> left) {
        if (other == left) return 1;
        else if (other.anchor == null) return -1;
        else return t.compareTo(other.anchor);
    }

    public String toString() {
        return nodeMap().toString();
    }



    //Indice 0 -> index of value, Indice 1 -> size
    //Only accessed when a lock is held
    static <T extends Comparable<T>> int findValueIndex(T t, Node<T> curr) {

        for (int i = 0; i < curr.lpSize(); ++i) {
            T v = curr.lpArray(i);
            if (v != null && t.compareTo(v) == 0) return i;
        }

        return -1;
    }



    static class Node<T extends Comparable<T>> {
        final T anchor;
        final Object[] array;
        final Lock lock;
        int size;
        volatile boolean marked;
        volatile Node<T> next;

        public Node(T anchor, int capacity) {
            this.anchor = anchor;
            this.array = new Object[capacity];
            this.lock = new ReentrantLock();
        }

        public Node(Object[] initialArray) {
            this.anchor = (T) initialArray[0];
            this.array = initialArray;
            this.lock = new ReentrantLock();

        }

        public Node(T anchor, Object[] array) {
            this.anchor = anchor;
            this.array = array;
            this.lock = new ReentrantLock();
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

        void soNext(Node<T> node) {
            NEXT.setRelease(this, node);
        }

        Node<T> lpNext() {
            return (Node<T>) NEXT.get(this);
        }

        boolean loMarked(){
            return (boolean) MARKED.getAcquire(this);
        }

        boolean lopMarked(){
            return (boolean) MARKED.getOpaque(this);
        }

        boolean lpMarked(){
            return (boolean) MARKED.get(this);
        }

        void soMarked(){
            MARKED.setRelease(this, true);
        }

        public Node<T> loNext() {
            return (Node<T>) NEXT.getAcquire(this);
        }

        public void spNext(Node<T> node) {
            NEXT.set(this, node);
        }

        @Override
        public String toString() {
            return anchor + " : " + Arrays.toString(array) + " -> " + next;
        }

        void increment(int by) {
            SIZE.getAndAddRelease(this, by);
        }

        void decrement() {
            SIZE.getAndAddRelease(this, -1);
        }

        int size() {
           return (int) SIZE.getAcquire(this);
        }

        int lpSize() {
            return (int) SIZE.get(this);
        }

        int arraySize() {
            int s = 0;
            for (int i = 0; i < array.length; ++i) {
                if (array[i] != null) ++s;
            }
            return s;
        }

    }

    static class SentinelNode<T extends Comparable<T>> extends Node<T>{

        public SentinelNode() {
            super(null, null);
        }

        @Override
        public String toString() {
            return "Sentinel -> " + next;
        }
    }

    private static final VarHandle MARKED;
    private static final VarHandle NEXT;
    private static final VarHandle ARRAY;
    private static final VarHandle SIZE;

    @Override
    public boolean isEmpty() {
        return left.next == right;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public List<T> toList() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        List<T> ls = new ArrayList<>();
        while (curr != r) {
            var arr = curr.array.clone();
            for (int i = 0; i < capacity; ++i) {
                T t = (T) arr[i];
                if (t != null) ls.add(t);
            }

            curr = curr.loNext();
        }

        return ls;
    }


    static class LocalArrays<T extends Comparable<T>> {
        //Used for storing pred and curr arrays;
        final Node<T>[] nodes; //0 - pred, 1 - curr
        //Used for storing indices to prevent extra traversals to calculate size;

        public LocalArrays() {
            this.nodes = new Node[2];
        }

        public Node<T>[] nodes() {
            return nodes;
        }

    }

    public enum Operation {
        ADD, REMOVE
    }


    static {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
            SIZE = l.findVarHandle(Node.class, "size", int.class);
            MARKED = l.findVarHandle(Node.class, "marked", boolean.class);
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
