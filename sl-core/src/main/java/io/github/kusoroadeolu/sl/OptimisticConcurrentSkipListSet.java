package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;


/*
* A lazy optimistic skip list which uses fine-grained locking for concurrency control.
*
* Atomic visibility of operations in skip lists is quite tricky, however, to ensure add / remove operations on a node appear atomically to other threads.
* each node contains a fully linked and marked boolean flags. A node can be said to be part of the list when fullyLinked = true and a node can be said to be effectively removed from the list when marked = true;
*
* Adds involve scanning the skip list (without acquiring any locks) up to a layer "x" and adding pred and succ nodes that enforce linear ordering of a non-null variable v at that layer, into preds and succs arrays
* While scanning if we encounter a node with a value equals to ours, we check if the value is marked, if the value is not marked and fullyLinked, we immediately return, otherwise, if the value is not fullyLinked, we spin until it becomes visible to us, and we return false, otherwise we proceed with scanning
* We then go through the preds and succs array, keeping track of the highest layer and locking only the pred node, if we encounter a node twice, that layer is skipped.
* A pair of pred and succ nodes are determined to be valid if they're both unmarked and pred#next == succ
* If all goes well, we then mark node as fully linked using a set release, this release fence ensures all of node's next writes are made immediately visible to other threads
*
* Removals involve scanning the skip list optimistically up to a layer "x" and adding pred and succ nodes. If no node was found we return
* Before deletion we check if the node is ok to delete or our local field is marked. A node is ok to delete if it is fullyLinked, not marked and was found at its max level.
* If a node passes these checks, we then lock the node, if the node is marked as deleted, we return false, otherwise, we unlock the node.
* Otherwise, we mark the node as deleted and set our local marked variable to true. The local marked variable prevents us from needlessly locking a node multiple times
* We then go through the preds and succs array, keeping track of the highest layer and locking only the pred node, if we encounter a node twice, that layer is skipped.
* We use a weaker validation form here. A pair of pred and succ nodes are determined to be valid if pred is unmarked and pred#next == succ
* During removals, we splice the list from its highest layer before unlocking the node
*
* To prevent deadlocks, across all operations which need a lock, locks are acquired from the highest layer to the lowest layer and released in the opposite manner
*
* Accesses to MARKED/FULLY_LINKED USE set_release/get_acquire mode.
*
* Some Implementation Details not included in the paper:
* 1. We use a node#value.compareTo(anotherNode) == 0 to indicate if a node is a duplicate in the set.
*  While this violates the Set contract, good reasons are proposed for it. Using a Object.equals() check incurs overhead.
* Under sequential constraints, this overhead might seems negligible, however in a tight loop, under high concurrency, this overhead adds up significantly.
* Through profiling, I discovered only the equality check took 4000 cpu samples. An object.equals to check involves around 4 operations needed by the JVM to ascertain equality, these include:
*   A virtual method dispatch
    An instanceof check inside equals()
    Unboxing the wrapped int value
    Then the actual comparison
* By shifting our equality comparison from the equals contract to the compare to contract, the JVM now only has to execute a single equality instruction
*
* This change dropped the CPU samples used in that hotpath from 4000 to approx. 1500 samples. A 70% drop, and our thrpt improved thereabout.
*
* 2. Profile data also shows that unnecessary time is spent at the max height even when the max height hasn't been reached yet.
* To prevent starting from the max height redundantly everytime,
* We keep track of the max height every node has visited, SO we start from there. There's a case where a node with the curr max height can be removed, we accept the trade-off to prevent unnecessary complexity.
* We use an ACQUIRE mode for reads on the add/remove to ensure immediate visibility while cas'ing as we don't want to waste time spinning on stale data.
* However on the contains method we use a weaker OPAQUE mode, which could return a value which lags behind but prevents a stronger synchronization form
* On the add side, we store a local variable which is constantly updated to prevent an extra acquire read after we've broken out of the cas loop. Though this is merely an optimization
 * */
/**
 * @author kusoroadeolu
 * */
@SuppressWarnings("unchecked")
public class OptimisticConcurrentSkipListSet<T extends Comparable<T>> implements Set<T> {
    private final Node<T> left;
    private final Node<T> right;
    private final int height;
    private final LongAdder size;
    private final ThreadLocal<Node<T>[]> preds;
    private final ThreadLocal<Node<T>[]> succs;
    private volatile int chl; //Current highest level used by the findNode method to avoid traversing from the max height every iteration
    private static final double PROBABILITY = 0.5;

    public OptimisticConcurrentSkipListSet(int height) {
        this.left = new LeftNode<>(null, height);
        this.right = new RightNode<>(null, height);
        this.height = height;
        this.preds = ThreadLocal.withInitial(() -> new Node[height]);
        this.succs = ThreadLocal.withInitial(() -> new Node[height]);
        this.size = new LongAdder();
        linkLeftRight();
    }

    void linkLeftRight(){
        var l = left;
        var r = right;
        for (int i = 0; i < height; ++i){
            l.setNextAt(i, r);
        }
    }

    int loCurrentMaxLevel(){
        return (int) CHL.getAcquire(this); //acquire
    }

    int lopCurrentMaxLevel(){
        return (int) CHL.getOpaque(this); //opaque
    }


    boolean casCurrMl(int seen, int ml) {
        return CHL.compareAndSet(this, seen, ml);
    }

    public boolean add(T t) {
        Objects.requireNonNull(t);
        int maxLevel = generateLevel();
        int seenMaxLevel = lopCurrentMaxLevel(); //Weak read
        int h = maxLevel > seenMaxLevel ? height - 1 : seenMaxLevel;  //If the max level is greater than seen max level, which just start from the height, otherwise we start from seen max level
        // A stale read is alright here
        Node<T>[] preds = this.preds.get();
        Node<T>[] succs = this.succs.get();
        outer: while (true) {
            int lFound = findNode(preds, succs, h ,t, Operation.ADD);
            if (lFound != -1) { //If we actually found a node
                Node<T> seen = succs[lFound];
                var marked = seen.loMarked();
                if (!marked) { //If not deleted
                    while (!seen.loFullyLinked()) Thread.onSpinWait(); //From my prev experience a spin wait over long periods of time could kill perf, we could park for 1 nanos here
                    // or though while we're violating the atomicity invariant of our linearizability point, we could just return
                    return false;
                }

                //If it is marked for deletion we retry here
                continue;
            }

            Node<T> pred, succ, prevPred = null;
            int hl = -1;
            try {
                for (int layer = 0; layer < maxLevel; ++layer) {
                    pred = preds[layer];
                    succ = succs[layer];

                    if (prevPred != pred){
                        pred.lock();
                        hl = layer;
                        prevPred = pred;
                    }

                    if (!validate(pred, succ, layer)) continue outer;
                }

                Node<T> node = new Node<>(t, maxLevel);
                for (int layer = 0; layer < maxLevel; ++layer) {
                    pred = preds[layer];
                    succ = succs[layer];

                    node.setNextAt(layer, succ);
                    pred.setNextAt(layer, node);
                }

                node.soFullyLinked(); //Linearization point, provides a release barrier, any thread that reads this will see our next links
                increment();

                while (maxLevel > seenMaxLevel && !casCurrMl(seenMaxLevel, maxLevel)) { //Only try to update it once we've actually linked
                    seenMaxLevel = loCurrentMaxLevel(); //Acquire read
                }

                return true;
            }finally {
                unlock(preds, hl);
            }
        }
    }

    public boolean remove(Object o) {
        Objects.requireNonNull(o);
        Node<T>[] preds = this.preds.get();
        Node<T>[] succs = this.succs.get();
        T t = (T) o;
        boolean isMarked = false;
        outer: while (true) {
            int lFound = findNode(preds, succs, loCurrentMaxLevel(), t , Operation.REMOVE); //We use a stronger read to prevent the
            if (lFound == -1) return false; //No node found

            Node<T> node = succs[lFound];
            int maxLevel = node.height;
            if (isMarked || okToDelete(node, lFound)) {
                if (!isMarked) {
                    node.lock();
                    if (node.lpMarked()) { //If this is marked, a plain read is ok here
                        node.unlock();
                        return false;
                    }

                    node.soMarked();
                    isMarked = true;
                }

                Node<T> pred, succ, prevPred = null;
                int hl = -1;
                try {
                    for (int layer = 0; layer < maxLevel; ++layer) {
                        pred = preds[layer];
                        succ = succs[layer];

                        if (prevPred != pred){
                            pred.lock();
                            hl = layer;
                            prevPred = pred;
                        }

                        if (!weakValidate(pred, succ, layer)) continue outer;
                    }


                    //Splice from max level downwards
                    for (int layer = maxLevel - 1; layer >= 0; --layer) {
                        pred = preds[layer];
                        pred.setNextAt(layer, node.nextAt(layer));
                    }

                    node.unlock();
                    decrement();
                    return true;
                }finally {
                    unlock(preds, hl);
                }

            } else return false;

        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(Object o) {
        T t = (T) o;
        Node<T>[] preds = this.preds.get();
        Node<T>[] succs = this.succs.get();
        int lFound = findNode(preds, succs, lopCurrentMaxLevel() , t, Operation.CONTAINS); //A weaker read is alright here, compared to remove/add, where we need stronger visibility guarantees
        if (lFound == -1) return false;
        var node = succs[lFound];
        return node.loFullyLinked() && !node.loMarked();
    }

    public int size(){
        return size.intValue();
    }

    boolean validate(Node<T> pred, Node<T> succ, int layer) {
        return !pred.loMarked() && !succ.loMarked() && pred.nextAt(layer) == succ;
    }

    //Here succ can be marked, ideally if succ is marked for deletion, its needs to obtain its pred node, which is us, so as long as we hold our node, succ cannot change
    //When succ eventually holds the node, it will be visible we are dead and restart
    boolean weakValidate(Node<T> pred, Node<T> succ, int layer) {
        return !pred.loMarked() && pred.nextAt(layer) == succ;
    }


    void unlock(Node<T>[] preds, int highestLocked) {
        if (highestLocked == -1) return;
        Node<T> prevPred = null;
        for (int layer = 0; layer <= highestLocked; layer++) {
            if (preds[layer] != prevPred) {
                preds[layer].unlock();
                prevPred = preds[layer];
            }
        }
    }

    boolean okToDelete(Node<T> node, int maxLevel) {
        return node.loFullyLinked() && !node.loMarked() && (node.height - 1) == maxLevel;
    }

    int findNode(Node<T>[] preds, Node<T>[] succs, int seen ,T t, Operation op) {
        int found = -1;
        var l = left;
        var r = right;
        Node<T> pred = l;
        for (int layer = seen; layer >= 0; --layer) {
            Node<T> curr = pred.nextAt(layer);
            int comp;
            while ((comp = compare(t, curr, r, l)) > 0){
                pred = curr; curr = pred.nextAt(layer);
            }

            if (found == -1 && comp == 0) {
                if (op == Operation.ADD || op == Operation.CONTAINS) {
                    preds[layer] = pred;
                    succs[layer] = curr;
                    return layer;
                }
                found = layer;
            }

            preds[layer] = pred;
            succs[layer] = curr;
        }

        return found;
    }

    int compare(T t, Node<T> curr, Node<T> r, Node<T> l) {
        if (curr == r) return -1;       // right sentinel, stop
        if (curr == l) return 1;     // left sentinel, keep going (shouldn't really happen)
        return t.compareTo(curr.v);
    }

    private int generateLevel() {
        double r = ThreadLocalRandom.current().nextDouble();
        int level = (int)(Math.log(r) / Math.log(PROBABILITY)) + 1;
        return Math.min(level, height);
    }

    @Override
    public Iterator<T> iterator() {
        return null;
    }


    @Override
    public Object[] toArray() {
        return new Object[0];
    }

    @Override
    public <T1> T1[] toArray(T1[] a) {
        return null;
    }

    @Override
    public boolean containsAll( Collection<?> c) {
        return false;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    @Override
    public void clear() {

    }


    static class Node<T extends Comparable<T>> {
        final T v;
        final Node<T>[] nexts;
        final int height;
        final Lock lock;
        volatile boolean fullyLinked;
        volatile boolean marked; //For deletion

        public Node(T v, int height) {
            this.v = v;
            this.height = height;
            this.nexts = new Node[height];
            this.lock = new ReentrantLock();
        }

        void lock(){
            lock.lock();
        }

        Node<T> nextAt(int i) {
            return nexts[i];
        }

        void setNextAt(int i, Node<T> next) {
            nexts[i] = next;
        }

        void soFullyLinked(){
            FULLY_LINKED.setRelease(this, true);
        }

        boolean loFullyLinked () {
            return (boolean) FULLY_LINKED.getAcquire(this);
        }

        boolean loMarked(){
            return (boolean) MARKED.getAcquire(this);
        }

        //Should only be called under a lock
        boolean lpMarked() {
            return (boolean) MARKED.get(this);
        }

        void soMarked() {
            MARKED.setRelease(this, true);
        }

        void unlock(){
            lock.unlock();
        }

        @Override
        public String toString() {
            return v == null ? null : v.toString();
        }
    }

    //Left sentinel node
    static class LeftNode<T extends Comparable<T>> extends Node<T>{


        public LeftNode(T v, int height) {
            super(null, height);
        }

        @Override
        public String toString() {
            return "LeftNode";
        }
    }

    static class RightNode<T extends Comparable<T>> extends Node<T> {
        public RightNode(T v, int height) {
            super(null, height);
        }

        @Override
        public String toString() {
            return "RightNode";
        }
    }

    //Can snapshot an inconsistent state
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < height; ++i) {
            sb.append("Layer ").append(i).append(": ");

            Node<T> curr = left.nextAt(i);
            while (curr != right) {
                Node<T> next = curr.nextAt(i);
                var append = curr.loFullyLinked() && !curr.loMarked();
                if (append) sb.append(curr);
                if (next != right) sb.append(", ");
                curr = next;
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private static final VarHandle FULLY_LINKED;
    private static final VarHandle MARKED;
    private static final VarHandle CHL;

    static {
        var l = MethodHandles.lookup();
        try {
            CHL = l.findVarHandle(OptimisticConcurrentSkipListSet.class, "chl", int.class);
            FULLY_LINKED = l.findVarHandle(Node.class, "fullyLinked", boolean.class);
            MARKED = l.findVarHandle(Node.class, "marked", boolean.class);
        }catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    enum Operation{
        ADD, REMOVE, CONTAINS
    }

    void increment() {
        size.increment();
    }

    void decrement(){
        size.decrement();
    }
}
