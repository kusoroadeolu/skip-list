package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import static io.github.kusoroadeolu.sl.EFUnrolledConcurrentList.Status.*;


/*
 * An unrolled concurrent linked list that combines elimination and flat combining.
 * Ideally elimination works horribly with linked lists, as removes and adds need to combine with very specific threads.
 * However rather than going the ideal elimination route and eliminating against opposite ops,
 * we use the elimination arena as a means to collide threads who have similar operations as us on a given node (as this is an unrolled list)
 * We then apply those threads' operations for them when we acquire the lock of that node, or if the node or its predecessor has been deleted, we force all threads to retry
 * Duplicates are allowed in this list to reduce complexity
 *
 *
 * This is pretty much experimental, and the results are really not that great due to all the
 * overhead of retrying if a node is deleted and the overhead of verifying i
 *  */
/**
 * @author kusoroadeolu
 * */
public class EFUnrolledConcurrentList<T extends Comparable<T>>  implements ConcurrentCollection<T> {

    private final AtomicIntegerArray arena;
    private final AtomicReferenceArray<ThreadNode<T>> locations;
    private final EFUnrolledLinkedList<T> list;
    private final ThreadLocal<EFUnrolledConcurrentList.LocalValues<T>> localValues;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();
    private static final int EMPTY = -1;


    public EFUnrolledConcurrentList() {
        this(64, 16);
    }


    public EFUnrolledConcurrentList(int arrCap, int minFull) {
        list = new EFUnrolledLinkedList<>(arrCap, minFull);
        AtomicInteger integer = new AtomicInteger();
        localValues = ThreadLocal.withInitial(() -> new EFUnrolledConcurrentList.LocalValues<>(integer.getAndIncrement()));
        arena = newCollisionArray();
        locations = new AtomicReferenceArray<>(NCPU);
    }

    public boolean add(T t) {
        var local = localValues.get();
        ThreadNode<T> node = new ThreadNode<>(t, Operation.ADD, local.index());
        if (list.add(node, local)) return true;
        doFind(node, local);
        return true;
    }

    public boolean remove(Object t) {
        var local = localValues.get();
        ThreadNode<T> node = new ThreadNode<>((T)t, Operation.REMOVE, local.index());
        boolean val = list.remove(node, local);
        if (val) return node.lpStatus() == FINISHED;;

        doFind(node, local);
        return node.loStatus() == FINISHED;
    }

    public boolean contains(Object o) {
        return list.contains(o, localValues.get());
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public List<T> toList() {
        return list.toList();
    }

    static AtomicIntegerArray newCollisionArray() {
        var arr = new AtomicIntegerArray(NCPU);
        for (int i = 0; i < NCPU; ++i) {
            arr.setRelease(i, EMPTY);
        }

        return arr;
    }

    void doFind(ThreadNode<T> ourNode, LocalValues<T> localValues) {
        var idx = ourNode.index;
        var l = locations;
        var ca = arena;
        var op = ourNode.operation;
        var s = this.list;
        ourNode.node = s.currNode(ourNode, localValues);
        l.setRelease(idx, ourNode);


        while (true) {
            int pos = ThreadLocalRandom.current().nextInt(NCPU); //random array collision position
            int theirIdx = getCollisionIndex(ca, idx, pos);  //Location we're colliding with
            if (theirIdx != EMPTY) {
                var theirNode = l.getAcquire(theirIdx); //Use an acquire read to ensure we always see the current node

                //The id check here is to ensure that another thread has not already swapped their thread info with this thread, and we aren't colliding with ourselves
                if (theirNode != null && theirIdx == theirNode.index && theirNode.operation == ourNode.operation && theirIdx != idx) {
                    //Try to make ourselves unavailable
                    if (l.compareAndSet(idx, ourNode, null)) {
                        //try to collide now
                        collide(ourNode, theirNode, l);
                        boolean succeed = op == Operation.ADD ? s.add(ourNode, localValues) : s.remove(ourNode, localValues);
                        if (succeed) break;

                    } else {
                        //If we can't make ourselves unavailable, another thread has collided with us, so we wait
                        var status = tryFinishCollide(ourNode);
                        if (status == NOT_FOUND || status == FINISHED) return;
                    }

                    ourNode.node = s.currNode(ourNode, localValues); //find a new curr node, backed by set release from idx
                    l.setRelease(idx, ourNode);
                    continue; //Immediately try and collide again,

                }
            }

            LockSupport.parkNanos(1);

            if (l.getAcquire(idx) == null || !l.compareAndSet(idx, ourNode, null)){
                var status = tryFinishCollide(ourNode);
                if (status == NOT_FOUND || status == FINISHED) return;
            }

            boolean succeed = op == Operation.ADD ? s.add(ourNode, localValues) : s.remove(ourNode, localValues);
            if (succeed) return;
            ourNode.node = s.currNode(ourNode, localValues); //Find first, before we add or remove, reduces the possibility of failure
            l.setRelease(idx, ourNode); //Re write our info
        }
    }

    int getCollisionIndex(AtomicIntegerArray arr, int ourIdx, int pos) {
        return arr.getAndSet(pos, ourIdx);
    }

    boolean collide(ThreadNode<T> ours, ThreadNode<T> theirs, AtomicReferenceArray<ThreadNode<T>> ara) {
        if (ara.compareAndSet(theirs.index, theirs, null)) {

            if (theirs.node != ours.node) { //Backed by set release on this index
                theirs.soRestart();
                return false; //restart (don't detach your prev nodes)
            }

            combine(ours, theirs);
            return true;
        }

        return false;
    }

    void combine(ThreadNode<T> ours, ThreadNode<T> theirs) {
        var l = ours.last;
        int theirSize = theirs.loSize();
        l.next = theirs;
        ours.last = theirs.last;
        ours.soSize(ours.lpSize() + theirSize); //Happens before visibility for last and next when colliding
    }

    Status tryFinishCollide(ThreadNode<T> ours) {
        int spins = 0;
        while (true) {
            var s = ours.loStatus();
            if (s == FINISHED) return s;
            else if (s == Status.RETRY) {
                ours.detach();
                ours.spInit(); //Plain write is alright since we're the only ones ever reading our own write
                return s;
            } else if (s == NOT_FOUND || s == RESTART) {
                ours.spInit();
                return s;
            }
            spins = backoffAfterXSpins(++spins);
        }

    }

    int backoffAfterXSpins(int spins) {
        if (spins < 64) {
            Thread.onSpinWait();
            return ++spins;
        } else {
            LockSupport.parkNanos(1);
            return 0;
        }
    }

    public List<T> anchorList() {
        return list.anchorList();
    }

    public Map<T, List<T>> nodeMap() {
        return list.nodeMap();
    }



    static class ThreadNode<T extends Comparable<T>> {
        final T value;
        final Operation operation;
        final int index; //Our index in the location array
        ConcurrentUnrolledSet.Node<T> node; //The node our value belongs to
        ThreadNode<T> next;
        ThreadNode<T> last;
        volatile int size; //Number of thread nodes, including ours
        volatile Status status;

        public ThreadNode(T value, Operation operation, int index) {
            this.value = value;
            this.operation = operation;
            this.index = index;
            last = this;
            this.size = 1;
            this.status = Status.INIT;
        }

        void detach(){
            next = null;
            last = this;
            soSize(1); //Detach
        }

        void soFinished() {
            STATUS.setRelease(this, FINISHED);
        }

        void spInit() {
            STATUS.set(this, Status.INIT);
        }

        int loSize() {
            return (int) SIZE.getAcquire(this);
        }

        void soSize(int i) {
            SIZE.setRelease(this, i);
        }

        int lpSize() {
            return (int) SIZE.get(this);
        }


        Status loStatus() {
            return (Status) STATUS.getAcquire(this);
        }

        void soRetry() {
            STATUS.setRelease(this, Status.RETRY);
        }

        void soRestart() {
            STATUS.setRelease(this, Status.RESTART);
        }

        void markAllRetry() {
            var h = next; //Don't mark ourselves to retry
            while (h != null) {
                h.soRetry();
                h = h.next;
            }

            detach();
        }

        public void soNotFound() {
            STATUS.setRelease(this, Status.NOT_FOUND);
        }

        public Status lpStatus() {
            return (Status) STATUS.get(this);
        }


    }

    static class LocalValues<T extends Comparable<T>> {
        //Used for storing pred and curr arrays;
        final ConcurrentUnrolledSet.Node<T>[] nodes; //0 - pred, 1 - curr
        final int collisionIndex;

        public LocalValues(int index) {
            this.nodes = new ConcurrentUnrolledSet.Node[2];
            this.collisionIndex = index;
        }

        public ConcurrentUnrolledSet.Node<T>[] nodes() {
            return nodes;
        }

        public int index() {
            return collisionIndex;
        }
    }

    enum Operation {
        ADD, REMOVE
    }

    enum Status {
        INIT, FINISHED, RETRY, RESTART ,NOT_FOUND
    }

    private static final VarHandle SIZE;
    private static final VarHandle STATUS;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            SIZE = l.findVarHandle(ThreadNode.class, "size", int.class);
            STATUS = l.findVarHandle(ThreadNode.class, "status", Status.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}