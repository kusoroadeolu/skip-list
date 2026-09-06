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
    private final int maxMerge;

    public UnrolledConcurrentList() {
        this(128, 16);
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
        maxMerge = (int) (0.75 * capacity);
    }


    public boolean add(T t) {
        Objects.requireNonNull(t);
        Node<T> l = left;
        Node<T> r = right;
        int aCap = capacity;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        var indices = localArrays.indices(); //Stores exists in array index and size respectively
        while (true) {
            if (isPresent(t, l, r ,nodes, aCap)) return false;
            var pred = nodes[0];
            var curr = nodes[1];

           if (pred.lvMarked() || curr.lvMarked()) continue;

           pred.lock();
            try {
                if (isNotValid(pred, curr)) continue;

                if (curr == r || t.compareTo(curr.anchor) < 0) {
                    Node<T> n = new Node<>(t, aCap);
                    n.soArray(0, t);
                    n.increment(1);
                    n.spNext(curr);
                    pred.soNext(n);
                    return true;
                }


                fillValueIndexAndSize(t, curr, aCap ,indices, Operation.ADD);
                int index = indices[0];
                if (index != -1) return false;


                int size = indices[1];
                int idx = findAvailableIndex(aCap, curr);

                if (size < aCap) {
                    curr.soArray(idx, t); //Linearization point
                    curr.increment(1);
                    return true;
                } else { //Split
                    curr.lock(); //Lock to ensure no one can modify curr.next during the split
                    // So we have a consistent view of curr.next from when we start the split operation
                    try {
                        var succ = curr.lpNext();
                        split(aCap ,t ,nodes);
                        var n1 = nodes[0];
                        var n2 = nodes[1];

                        curr.soMarked();

                        n1.spNext(n2);
                        n2.spNext(succ);
                        pred.soNext(n1); //Linearization point
                        //Should provide more than enough synchronization to allow visibility of next writes
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
        int aCap = capacity;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        var indices = localArrays.indices();
        while (true) {
            if (!isPresent(t, l, r ,nodes, aCap)) return false;
            var pred = nodes[0];
            var curr = nodes[1];

            if (pred.lvMarked() || curr.lvMarked()) continue; //At this point, next we can be sure next writes haven't

            pred.lock();
            try {
                if (isNotValid(pred, curr)) continue;
                fillValueIndexAndSize(t, curr, aCap ,indices, Operation.REMOVE);
                int index = indices[0];
                int size = indices[1];

                if (index  == -1) return false;

                nullifyIndex(index, aCap ,curr);
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
                        int[] emptyIndexes = new int[succSize];
                        findEmptyIndexes(emptyIndexes, aCap ,curr);
                        //                Node map: {4=[4, 10, 2], 16=[19]}
                        if (total <= maxMerge) { // Merge to fill the lower indices
                            merge(curr, succ, aCap ,emptyIndexes);
                        } else { //Redistribute so the lower index is not sparse
                            redistribute(curr, succ, succSize, aCap ,total);
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
        } while (curr.lvMarked());

        if (curr == r || curr.anchor.compareTo(t) > 0) return false;

        for (int i = capacity - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }



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

    static <T extends Comparable<T>>int findAvailableIndex(int arrayCap,  Node<T> node) {
        for (int i = 0; i < arrayCap; ++i) {
            if (node.lpArray(i) == null) {
                return i;
            }
        }

        return -1;
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

    static <T extends Comparable<T>>void merge(Node<T> curr, Node<T> succ, int arrayCap ,int[] indexes) {
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = succ.lpArray(i);
            if (t != null) {
                var idx = indexes[j++];
                curr.soArray(idx, t);

            }
        }

        succ.soMarked();

        curr.increment(succ.size());
        curr.soNext(succ.lpNext()); //Plain read for succ as we already hold its lock

    }


    static <T extends Comparable<T>>void redistribute(Node<T> curr, Node<T> succ, int succSize, int arrayCap ,int total) {
        Object[] copy = filterNulls(succ.array, succSize);

        int nodeCount = total / 2;
        int toMove = succSize - nodeCount;
        Arrays.sort(copy);

        var nodeArr = Arrays.copyOf(copy, arrayCap);
        var node = new Node<>((T) nodeArr[toMove], nodeArr);
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            if (j == toMove) break;
            if (curr.lpArray(i) == null) {
                curr.soArray(i, (T) nodeArr[j]);
                nodeArr[j++] = null;
            }
        }


        succ.soMarked();

        curr.increment(toMove);
        node.increment(succSize - toMove);

        node.spNext(succ.lpNext());
        curr.soNext(node);
    }

    /* 5, 10 = 15
     * Total = currSize + succSize
     * toMove = 10 - 7 = 3
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

    static <T extends Comparable<T>> void nullifyIndex(int index, int arrayCap ,Node<T> curr) {
        var arr = curr.array;
        int nnIndex = findNonNullIndex(arr, arrayCap ,index);
        if (nnIndex != -1 && index < nnIndex) { //Array is logically empty
            //We don't want to swap a value at a near index to a farther index.
            // For example if the index we're removing is 6 and the next non-null index is 2, we don't want to swap it
            //Here the set invariant is briefly violated though no reader thread will ever see duplicates
            curr.spArray(index, curr.lpArray(nnIndex)); //Move the value at nnIndex forward first before nulling out
            curr.soArray(nnIndex, null);
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

    static <T extends Comparable<T>>boolean isPresent(T t, Node<T> left, Node<T> right ,Node<T>[] nodes, int arrayCap){
        findNode(t, left, right, nodes);
        var curr = nodes[1];

        if (curr == right || curr.lvMarked() || curr.anchor.compareTo(t) > 0) return false;

        for (int i = arrayCap - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }


    static <T extends Comparable<T>>void findNode(T t, Node<T> left, Node<T> right ,Node<T>[] nodes) {
        Node<T> pred = left;
        Node<T> curr = left.loNext();
        while (curr != right) {
            Node<T> next = curr.loNext();
            if (next == right || t.compareTo(next.anchor) < 0) break;
            pred = curr;
            curr = next;
        }

        nodes[0] = pred; nodes[1] = curr;
    }

    public String toString() {
        return nodeMap().toString();
    }



    //Indice 0 -> index of value, Indice 1 -> size
    //Only accessed when a lock is held
    static <T extends Comparable<T>>void fillValueIndexAndSize(T t, Node<T> curr, int arrayCap ,int[] indices, Operation op) {
        indices[0] = -1;
        indices[1] = 0;

        for (int i = 0; i < arrayCap; ++i) {
            T v = curr.lpArray(i);
            if (v != null && t.compareTo(v) == 0) {
                indices[0] = i;
                if (op == Operation.ADD) return;
                break;
            }
        }

        indices[1] = curr.size();
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

        boolean lvMarked(){
            return (boolean) MARKED.getVolatile(this);
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

        public String asString() {
            return anchor + " : " + Arrays.toString(array);
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
        final int[] indices; // 0 - index, 1 - size

        public LocalArrays() {
            this.nodes = new Node[2];
            this.indices = new int[2];
        }

        public Node<T>[] nodes() {
            return nodes;
        }

        public int[] indices() {
            return indices;
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
