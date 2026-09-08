package io.github.kusoroadeolu.sl;

import io.github.kusoroadeolu.sl.EliminationNode.ThreadInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static io.github.kusoroadeolu.sl.ConcurrentUnrolledSet.Operation;
import static io.github.kusoroadeolu.sl.EliminationNode.NCPU;

/*
An elimination based unrolled linked list.
This builds on the previous experiment I tried by combining elimination
and flat combining on the whole list structure, however after some speculative profiling,
I discovered the two main contention points were
1. The locks per node (as they don't allow for concurrency)
2. The findNode scan of the list

This structure attempts to solve the first problem by allowing for greater concurrency per node
using elimination. Elimination per node allows for adds and removes to pair up in a smaller keyspace
unlike eliminating across the entire list, where adds and removes will have a harder time eliminating each other

The core algo is simply
while true
   findPredAndCurr
   checkIfValid
   tryLockPred
   else move to the elimination arena
   retry until success

We use a simple implementation of the elimination arena, which only requires one elimination array,
inspired from an elimination arena from ben manes and the Exchanger class. The main goal of this class is to see if write perf
improves under contention. However under low and medium contention, I actually expect this to have worse thrpt and latency that
the std structure. Due to the fact that under low contention, threads might be spread across the array,
nullifying the effects of per node concurrency. Another potential benchmark note, this structure might gain a lot when threads are operating
with values with close key spaces, so threads collide more often in nodes near to each other
*/
/**
 * @author kusoroadeolu
 * */
public class ConcurrentEliminationUnrolledSet<T extends Comparable<T>> implements ConcurrentCollection<T> {
    private final int arrayCap;
    private final int minFull;
    private final int maxMerge;
    private final EliminationNode<T> left;
    private final EliminationNode<T> right;
    private final ThreadLocal<LocalArrays<T>> localArrays;

    private static final Object FREE = null;
    private static final int MAX_SPINS = 512;
    private static final int SLOT_SPINS = 128;
    private static final int ARENA_LEN = 4;
    private static final int ARENA_MASK = ARENA_LEN - 1;

    public ConcurrentEliminationUnrolledSet() {
        this(64, 16);
    }

    public ConcurrentEliminationUnrolledSet(int arrCap, int minFull) {
        this.left = new EliminationNode.SentinelEliminationNode<>();
        this.right = new EliminationNode.SentinelEliminationNode<>();
        left.lock();
        try {
            left.next = right;
        }finally {
            left.unlock();
        }

        localArrays = ThreadLocal.withInitial(LocalArrays::new);
        this.minFull = minFull;
        this.arrayCap = arrCap;
        maxMerge = (int) (0.75 * arrCap);
    }

    public boolean add(T t) {
        Objects.requireNonNull(t);
        EliminationNode<T> l = left;
        EliminationNode<T> r = right;
        int aCap = arrayCap;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        ThreadInfo<T> info = null;
        while (true) {
            findNode(t, l, r, nodes);
            var pred = nodes[0];
            var curr = nodes[1];

            if (pred.lopMarked()) continue;

            if (pred.tryLock()) {
                try {
                    if (isNotValid(pred, curr)) continue;

                    if (curr == r || t.compareTo(curr.anchor) < 0) {
                        EliminationNode<T> n = new EliminationNode<>(t, aCap);
                        n.soArray(0, t);
                        n.increment(1);
                        n.spNext(curr);
                        pred.soNext(n);
                        return true;
                    }

                    int valueIndex = findValueIndex(t, curr);

                    if (valueIndex != -1) return false;

                    int size = curr.lpSize();

                    if (size < aCap) {
                        curr.soArray(size, t); //Linearization point
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
                            return true;
                        }finally {
                            curr.unlock();
                        }
                    }

                }finally {
                    pred.unlock();
                }
            }else {
                if (info == null)
                    info = new ThreadInfo<>(t, Operation.ADD);
                var arena = curr.arena;
                int start = ThreadLocalRandom.current().nextInt();
                if (scanAndMatch(info, arena, start) || awaitExchange(info, curr ,arena, start)) return true;
            }
        }
    }

    public boolean remove(Object o) {
        T t = (T) Objects.requireNonNull(o);
        EliminationNode<T> l = left;
        EliminationNode<T> r = right;
        int aCap = arrayCap;
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        ThreadInfo<T> info = null;
        boolean unlocked = false;
        while (true) {
            if (!isPresent(t, l, r ,nodes, aCap)) {
                //Try to scan and match
                if (info == null) info = new ThreadInfo<>(t, Operation.REMOVE);
                return scanAndMatch(info, nodes[1].arena, ThreadLocalRandom.current().nextInt());
            }
            var pred = nodes[0];
            var curr =  nodes[1];
            if (pred.lopMarked()) continue;

            if (pred.tryLock()) {
                try {
                    if (isNotValid(pred, curr)) continue;
                    int index = findValueIndex(t, curr);
                    int size = curr.lpSize();

                    if (index  == -1) {
                        pred.unlock();
                        unlocked = true;
                        //Try to scan and match incase an add thread came while we held the lock
                        if (info == null) info = new ThreadInfo<>(t, Operation.REMOVE);
                        return scanAndMatch(info, curr.arena, ThreadLocalRandom.current().nextInt());
                    }

                    removeValueAtIndex(index, size ,curr);
                    curr.decrement();
                    int currSize = size - 1;

                    if (currSize > minFull) return true;
                    curr.lock();
                    try {
                        var succ = curr.lpNext();
                        if (currSize == 0) {
                            curr.soMarked(); //Could we use a weaker mode for marked, maybe use the next write as a HB relationship. The issue though is
                            //a thread has previously read prev and its next flag, it context switches, another thread adds and then marks
                            pred.soNext(succ);
                            return true;
                        }

                        if (succ == r) return true;

                        succ.lock(); //Ensure we lock succ to prevent other threads from making structural modifications to its array
                        try {
                            int succSize = succ.size();
                            int total = currSize + succSize;
                            //                Node map: {4=[4, 10, 2], 16=[19]}
                            if (total <= maxMerge) { // Merge to fill the lower indices
                                merge(curr, succ, total);
                            } else { //Redistribute so the lower index is not sparse
                                redistribute(curr, succ, aCap ,total);
                            }

                            return true;
                        }finally {
                            succ.unlock();
                        }

                    }finally {
                        curr.unlock();
                    }


                }finally {
                    if (!unlocked) pred.unlock();
                }
            } else {
                if (info == null)
                    info = new ThreadInfo<>(t, Operation.REMOVE);
                int start = ThreadLocalRandom.current().nextInt();
                var arena = curr.arena;
                if (scanAndMatch(info, arena, start) || awaitExchange(info, curr ,arena, start)) return true;
            }
        }
    }

    //We intentionally don't start at zero to allow threads to spread out across the array and prevent contention at the ('0') index
    boolean scanAndMatch(ThreadInfo<T> ours, AtomicReferenceArray<ThreadInfo<T>> arena, int start){
        for (int i = 0; i < ARENA_LEN; ++i) {
            int slot = (start + i) & ARENA_MASK;
            ThreadInfo<T> theirs = arena.getAcquire(slot);
            if (theirs != free() && theirs.op() != ours.op()
                    && theirs.value() == ours.value()
                    && arena.compareAndSet(slot, theirs, free())) {
                return true;
            }
        }

        return false;
    }

    boolean awaitExchange(ThreadInfo<T> ours, EliminationNode<T> curr ,AtomicReferenceArray<ThreadInfo<T>> arena, int start) {
        for (int i = 0, totalSpins = 0; !curr.loMarked() &&  totalSpins < MAX_SPINS && i < ARENA_LEN; ++i){
            int slot = (start + i) & ARENA_MASK;
            ThreadInfo<T> theirs = arena.getAcquire(slot);
            if (theirs == free()) {
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
            } else if (theirs.op() != ours.op() && theirs.value() == ours.value()
                    && arena.compareAndSet(slot, theirs, free())) {
                return true;
            }

            Thread.yield();
        }

        return false; //Failed to match
    }

    // ======================= COPIED METHODS FROM UNROLLED ADAPTED TO USE ELIMINATION NODE ==============================

    static <T extends Comparable<T>>void split(int capacity ,T t ,EliminationNode<T>[] nodes) {
        int len = capacity + 1;

        Object[] copy = Arrays.copyOf(nodes[1].array, len); //Copy to prevent modifying the initial array
        copy[capacity] = t;


        Arrays.sort(copy);
        Object[] arr1 = new Object[capacity];
        Object[] arr2 = new Object[capacity];

        int half = len >>> 1;
        int rem = len - half;
        System.arraycopy(copy, 0, arr1, 0, half);
        System.arraycopy(copy, half, arr2, 0, rem);
        var n1 = new EliminationNode<T>(arr1);
        var n2 = new EliminationNode<T>(arr2);

        n1.increment(half);
        n2.increment(rem);

        nodes[0] = n1;
        nodes[1] = n2;
    }


    @Override
    public boolean isEmpty() {
        return false;
    }

    public boolean contains(Object o) {
        T t = (T) Objects.requireNonNull(o);
        var localArrays = this.localArrays.get();
        var nodes = localArrays.nodes();
        EliminationNode<T> curr;
        EliminationNode<T> l = left, r = right;

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

    @Override
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

    @Override
    public int size() {
        return 0;
    }

    static <T extends Comparable<T>>boolean isPresent(T t, EliminationNode<T> left, EliminationNode<T> right , EliminationNode<T>[] nodes, int arrayCap){
        findNode(t, left, right, nodes);
        var curr = nodes[1];

        if (curr == right || curr.loMarked() || curr.anchor.compareTo(t) > 0) return false;

        for (int i = arrayCap - 1; i >= 0; --i) {
            T v = curr.loArray(i);
            if (v != null && t.compareTo(v) == 0) return true;
        }

        return false;
    }


    static <T extends Comparable<T>>void findNode(T t, EliminationNode<T> left, EliminationNode<T> right ,EliminationNode<T>[] nodes) {
        EliminationNode<T> pred = left;
        EliminationNode<T> curr = left.loNext();
        while (curr != right) {
            EliminationNode<T> next = curr.loNext();
            if (next == right || t.compareTo(next.anchor) < 0) break;
            pred = curr;
            curr = next;
        }

        nodes[0] = pred; nodes[1] = curr;
    }

    static <T extends Comparable<T>>void merge(EliminationNode<T> curr, EliminationNode<T> succ, int totalSize) {
        int j = 0;
        for (int i = curr.size(); i < totalSize; ++i) {
            curr.soArray(i, succ.lpArray(j++));
        }

        succ.soMarked();

        curr.increment(succ.size());
        curr.soNext(succ.lpNext()); //Plain read for succ as we already hold its lock

    }


    static <T extends Comparable<T>>void redistribute(EliminationNode<T> curr, EliminationNode<T> succ, int capacity, int totalSize) {
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

        var newNode = new EliminationNode<>((T) sorted[elemsForCurr], nodeArray);

        succ.soMarked();

        curr.increment(elemsForCurr);
        newNode.increment(succSize - elemsForCurr);

        newNode.spNext(succ.lpNext());
        curr.soNext(newNode);
    }


    static <T extends Comparable<T>> int findValueIndex(T t, EliminationNode<T> curr) {
        for (int i = 0; i < curr.lpSize(); ++i) {
            T v = curr.lpArray(i);
            if (v != null && t.compareTo(v) == 0) return i;
        }

        return -1;
    }


    static <T extends Comparable<T>> void removeValueAtIndex(int index, int size , EliminationNode<T> curr) {
        int replacement = size - 1;
        if (index < replacement) { //We were the last value in the array
            curr.soArray(index, curr.lpArray(replacement)); //Move the value at swapIndex forward first before nulling out
            curr.spArray(replacement, null);
        } else {
            curr.soArray(index, null);
        }

    }

    static <T extends Comparable<T>>boolean isNotValid(EliminationNode<T> pred, EliminationNode<T> curr) {
        return pred.lpMarked() || curr.lpMarked() || pred.lpNext() != curr;
    }


    static class LocalArrays<T extends Comparable<T>> {
        //Used for storing pred and curr arrays;
        final EliminationNode<T>[] nodes; //0 - pred, 1 - curr
        //Used for storing indices to prevent extra traversals to calculate size;

        public LocalArrays() {
            this.nodes = new EliminationNode[2];
        }

        public EliminationNode<T>[] nodes() {
            return nodes;
        }


    }


    public static <T extends Comparable<T>> ThreadInfo<T> free() {
        return  (ThreadInfo<T>) FREE;
    }
}
