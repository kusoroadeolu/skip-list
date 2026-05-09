package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Lock;
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
* Accesses to MARKED/FULLY_LINKED USE set_release/get_acquire mode. Concrete arguments could be made on the usage of opaque modes though.
* */
@SuppressWarnings("unchecked")
public class OptimisticConcurrentSkipListSet<T extends Comparable<T>> {
    private final Node<T> left;
    private final Node<T> right;
    private final int height;
    private volatile int size;
    private final ThreadLocal<Node<T>[]> preds;
    private final ThreadLocal<Node<T>[]> succs;
    private static final double PROBABILITY = 0.5;

    public OptimisticConcurrentSkipListSet(int height) {
        this.left = new LeftNode<>(null, height);
        this.right = new RightNode<>(null, height);
        this.height = height;
        this.preds = ThreadLocal.withInitial(() -> new Node[height]);
        this.succs = ThreadLocal.withInitial(() -> new Node[height]);
        linkLeftRight();
    }

    void linkLeftRight(){
        var l = left;
        var r = right;
        for (int i = 0; i < height; ++i){
            l.setNextAt(i, r);
        }
    }

    public boolean add(T t) {
        Objects.requireNonNull(t);
        int maxLevel = generateLevel();
        int h = height;
        Node<T> node = new Node<>(t, maxLevel);
        Node<T>[] preds = this.preds.get();
        Node<T>[] succs = this.succs.get();
        outer: while (true) {
            int lFound = findNode(preds, succs, node, Operation.ADD);
            if (lFound != -1) { //If we actually found a node
                Node<T> seen = succs[lFound];
                var marked = seen.loMarked();
                if (!marked) { //If not deleted
                    while (!seen.loFullyLinked()) Thread.onSpinWait();
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

                for (int layer = 0; layer < maxLevel; ++layer) {
                    pred = preds[layer];
                    succ = succs[layer];

                    node.setNextAt(layer, succ);
                    pred.setNextAt(layer, node);
                }

                node.soFullyLinked(); //Linearization point
                increment();
                return true;
            }finally {
                unlock(preds, hl);
            }
        }
    }

    boolean validate(Node<T> pred, Node<T> succ, int layer) {
       return !pred.loMarked() && !succ.loMarked() && pred.nextAt(layer) == succ;
    }

    //Here succ can be marked, ideally if succ is marked for deletion, its needs to obtain its pred node, which is us, so as long as we hold our node, succ cannot change
    //When succ eventually holds the node, it will be visible we are dead and restart
    boolean weakValidate(Node<T> pred, Node<T> succ, int layer) {
        return !pred.loMarked() && pred.nextAt(layer) == succ;
    }

    public boolean remove(Object o) {
        Objects.requireNonNull(o);
        Node<T>[] preds = this.preds.get();
        Node<T>[] succs = this.succs.get();
        Node<T> dummy = new SearchNode<>((T) o);
        boolean isMarked = false;
        outer: while (true) {
            int lFound = findNode(preds, succs, dummy, Operation.REMOVE);
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


                    //Splice from max level
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

            }

        }
    }

    //Here we want to copy the node we find, though it adds more object creation overhead, it ensures we get a consistent snapshot of the node while we're inspecting it
    public boolean contains(Object o) {
        T t = (T) o;
        Node<T>[] preds = this.preds.get();
        Node<T>[] succs = this.succs.get();
        int lFound = findNode(preds, succs, new SearchNode<>(t), Operation.CONTAINS);
        if (lFound == -1) return false;
        IO.println("Found: " + succs[lFound].v);
        var copy = succs[lFound].copy();
        return copy.fullyLinked && !copy.marked;
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

    int findNode(Node<T>[] preds, Node<T>[] succs, Node<T> node, Operation op) {
        int found = -1;
        Node<T> pred = left;
        var r = right;
        for (int layer = height - 1; layer >= 0; --layer) {
            Node<T> curr = pred.nextAt(layer);
            while (curr != r && node.compareTo(curr) > 0){
                pred = curr; curr = pred.nextAt(layer);
            }

            if (found == - 1 && Objects.equals(node.v, curr.v)) {
                if (op == Operation.ADD || op == Operation.CONTAINS) {
                    preds[layer] = pred;
                    succs[layer] = curr;
                    return layer; //End
                }
                found = layer;
            }

            preds[layer] = pred;
            succs[layer] = curr;
        }

        return found;
    }

    private int generateLevel() {
        double r = ThreadLocalRandom.current().nextDouble();
        int level = (int)(Math.log(r) / Math.log(PROBABILITY)) + 1;
        return Math.min(level, height);
    }


    static class CopyNode {
        final boolean fullyLinked;
        final boolean marked;

        public CopyNode(boolean fullyLinked, boolean marked) {
            this.fullyLinked = fullyLinked;
            this.marked = marked;
        }
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

        public Node(T v, int height, Lock lock) {
            this.v = v;
            this.height = height;
            this.nexts = new Node[height];
            this.lock = lock;
        }

        CopyNode copy(){
            return new CopyNode(fullyLinked, marked);
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

        int compareTo(Node<T> other){
            if (other instanceof OptimisticConcurrentSkipListSet.LeftNode<T>) return 1; //Left sentinel node
            else if (other instanceof OptimisticConcurrentSkipListSet.RightNode<T>) return -1; //Right sentinel node
            else return v.compareTo(other.v);
        }

        @Override
        public String toString() {
            return v == null ? null : v.toString();
        }
    }

    //Left sentinel node, when compareTo is called on this node,
    static class LeftNode<T extends Comparable<T>> extends Node<T>{


        public LeftNode(T v, int height) {
            super(null, height);
        }
    }

    static class RightNode<T extends Comparable<T>> extends Node<T> {
        public RightNode(T v, int height) {
            super(null, height);
        }
    }

    //Purely for searching
    static class SearchNode<T extends Comparable<T>> extends Node<T> {

        public SearchNode(T v, int height, Lock lock) {
            super(v, height, lock);
        }

        public SearchNode(T v) {
            this(v, 0, null);
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
                sb.append(curr);
                if (curr.nextAt(i) != right)sb.append(", ");
                curr = curr.nextAt(i);
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    private static final VarHandle FULLY_LINKED;
    private static final VarHandle MARKED;
    private static final VarHandle SIZE;

    static {
        var l = MethodHandles.lookup();
        try {
            SIZE = l.findVarHandle(OptimisticConcurrentSkipListSet.class, "size", int.class);
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
        SIZE.setVolatile(this, 1);
    }

    void decrement(){
        SIZE.setVolatile(this, -1);
    }
}
