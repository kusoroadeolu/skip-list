package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// Based on the paper https://www.researchgate.net/publication/220440170_A_Lazy_Concurrent_List-Based_Set_Algorithm
/* The code is quite easy to reason about
* The main idea of the paper is we optimistically scan for a window where two nodes might operate before we acquire locks.
* To propagate deletes before the actual unlinking happens, we lazily mark nodes as deleted, so threads waiting upon those nodes will validate and retry if needed
* Read operations however are wait free, as threads need not acquire locks during traversal and visibility is ensured by happens before guarantee of locks
*
* A simple optimization made in this impl is validation the state of a node before acquiring the node locks
* * */
/**
 * @author kusoroadeolu
 * */
public class LazyOptimisticSet<T extends Comparable<T>> implements ConcurrentCollection<T> {

    private final Node<T> left;
    private final Node<T> right;

    public LazyOptimisticSet() {
        this.left = new Node<>(null);
        this.right = new Node<>(null);
        left.lock();
        try {
            left.soNext(right);
        }finally {
            left.unlock();
        }

    }

    @Override
    public boolean add(T t) {
        var l = left;
        var r = right;

        while (true) {
            Node<T> pred = l;
            Node<T> curr = l.loNext();
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.loNext();
            }

            if (res == 0 && !curr.loMarked()) return false;

            //A - B - C
            if (pred.loMarked() || curr.loMarked()) continue;

            try {
                pred.lock();
                if (pred.lpMarked() || pred.lpNext() != curr) continue;
                try {
                    curr.lock();
                    if (curr.lpMarked()) continue;
                    Node<T> node = new Node<>(t);
                    node.spNext(curr);
                    pred.soNext(node); //Order here matters, we need to ensure node#next is set before we link pred to node. Set release ensures node write is visible
                    return true;
                }finally {
                    curr.unlock();
                }
            } finally {
                pred.unlock();
            }
        }

    }

    int compare(T t, Node<T> l, Node<T> r, Node<T> curr) {
        if (curr == r) return -1;
        if (curr == l) return 1;
        return t.compareTo(curr.t);
    }

    @Override
    public boolean remove(Object o) {
        T t = (T) o;
        var l = left;
        var r = right;

        while (true) {
            Node<T> pred = l;
            Node<T> curr = l.loNext();
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.loNext();
            }

            if (res != 0 || curr.loMarked()) return false;

            if (pred.loMarked()) continue;

            try {
                pred.lock();
                if (pred.lpMarked() || pred.lpNext() != curr) continue; //Validate before trying to acquire curr lock
                try {
                    curr.lock();
                    if (curr.lpMarked()) return false;

                    pred.soNext(curr.lpNext());
                    curr.soMarked();
                    return true;
                }finally {
                    curr.unlock();
                }
            } finally {
                pred.unlock();
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    public List<T> toList() {
        var l = left;
        var r = right;
        var curr = l.next;
        var ls = new ArrayList<T>();
        while (curr != r && !curr.loMarked()) {
            ls.add(curr.t);
            curr = curr.next;
        }

        return ls;
    }

    @Override
    public boolean contains(Object o) {
        T t = (T) o;
        var l = left;
        var r = right;
        Node<T> pred = l;
        Node<T> curr = pred.loNext();
        int res;
        while ((res = compare(t, l, r, curr)) > 0) {
            pred = curr; curr = pred.loNext();
        }

        return res == 0 && !curr.loMarked();
    }

    @Override
    public int size() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        int size = 0;
        while (curr != r) {
            if (!curr.loMarked()) ++size;
            curr = curr.loNext();
        }

        return size;
    }

    static class Node<T> {
        final T t;
        volatile boolean marked;
        final Lock lock;
        volatile Node<T> next;

        public Node(T t) {
            this.t = t;
            this.marked = false;
            this.lock = new ReentrantLock();
        }

        //Protected by the lock
        void soMarked() {
            MARKED.setRelease(this, true);
        }

        boolean loMarked() {
            return (boolean) MARKED.getAcquire(this);
        }

        Node<T> loNext() {
            return (Node<T>) NEXT.getAcquire(this);
        }

        Node<T> lpNext() {
            return (Node<T>) NEXT.get(this);
        }

        void soNext(Node<T> node) {
            NEXT.setRelease(this, node);
        }

        void spNext(Node<T> node) {
            NEXT.set(this, node);
        }

        boolean lpMarked() {
            return (boolean) MARKED.get(this);
        }

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();
        }
    }

    private static final VarHandle NEXT;
    private static final VarHandle MARKED;

    static {
        var l = MethodHandles.lookup();
        try {
            NEXT = l.findVarHandle(LazyOptimisticSet.Node.class, "next", Node.class);
            MARKED = l.findVarHandle(LazyOptimisticSet.Node.class, "marked", boolean.class);
        }catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
