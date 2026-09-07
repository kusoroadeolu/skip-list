package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author kusoroadeolu
 * */
//So similar to the lazy optimistic list, but instead we just use a fat lock instead of a lock per node
public class LazyOptimisticCoarseSet<T extends Comparable<T>> implements ConcurrentCollection<T> {
    private final Node<T> left;
    private final Node<T> right;
    private final Lock lock;

    public LazyOptimisticCoarseSet() {
        this.left = new Node<>(null);
        this.right = new Node<>(null);
        lock = new ReentrantLock();
        lock.lock();
        try {
            left.soNext(right);
        }finally {
            lock.unlock();
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

            var lc = lock;
            lc.lock();
            try {
                if (pred.lpMarked() || curr.lpMarked() || pred.lpNext() != curr) continue;
                Node<T> node = new Node<>(t);
                node.spNext(curr);
                pred.soNext(node); //Order here matters, we need to ensure node#next is set before we link pred to node. Set release ensures node write is visible
                return true;
            } finally {
                lc.unlock();
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

            var lc = lock;
            try {
                lc.lock();
                if (pred.lpMarked() || pred.lpNext() != curr) continue; //Validate before trying to acquire curr lock
                if (curr.lpMarked()) return false;
                curr.soMarked();
                pred.soNext(curr.lpNext()); //Order here matters, we need to ensure node#next is set before we link pred to node
                return true;
            } finally {
                lc.unlock();
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

    }

    private static final VarHandle NEXT;
    private static final VarHandle MARKED;

    static {
        var l = MethodHandles.lookup();
        try {
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
            MARKED = l.findVarHandle(Node.class, "marked", boolean.class);
        }catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

}
