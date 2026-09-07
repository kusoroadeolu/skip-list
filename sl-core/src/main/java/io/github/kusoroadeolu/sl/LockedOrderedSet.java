package io.github.kusoroadeolu.sl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author kusoroadeolu
 * */
public class LockedOrderedSet<T extends Comparable<T>> implements ConcurrentCollection<T> {
    private final Node<T> left;
    private final Node<T> right;
    private int size;
    private final Lock lock;

    public LockedOrderedSet() {
        this.left = new Node<>(null);
        this.right = new Node<>(null);
        this.lock = new ReentrantLock();
        lock.lock();
        try {
            left.next = right;
        }finally {
            lock.unlock();
        }
    }

    @Override
    public boolean add(T t) {
        var l = left;
        var r = right;
        var lc = lock;
        lc.lock();
        try {
            Node<T> pred = l;
            Node<T> curr = l.next;
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.next;
            }

            if (res == 0) return false;

            Node<T> node = new Node<>(t);
            node.next = curr;
            pred.next = node;
            ++size;
            return true;
        }finally {
            lc.unlock();
        }
    }

    @Override
    public boolean remove(Object o) {
        T t = (T) o;
        var l = left;
        var r = right;
        var lc = lock;
        lc.lock();
        try {
            Node<T> pred = l;
            Node<T> curr = l.next;
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.next;
            }

            if (res != 0) return false;

            pred.next = curr.next;
            --size;
            return true;
        }finally {
            lc.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        T t = (T) o;
        var l = left;
        var r = right;
        var lc = lock;
        lc.lock();
        try {
            Node<T> pred = l;
            Node<T> curr = l.next;
            int res;
            while ((res = compare(t, l, r, curr)) > 0) {
                pred = curr; curr = pred.next;
            }

            return res == 0;
        }finally {
            lc.unlock();
        }
    }

    @Override
    public int size() {
        var lc = lock;
        lc.lock();
        try {
            return size;
        }finally {
            lc.unlock();
        }
    }

    @Override
    public List<T> toList() {
        lock.lock();
        try {
            var curr = left.next;
            var ls = new ArrayList<T>();
            while (curr != right) {
                ls.add(curr.t);
                curr = curr.next;
            }
            return ls;
        }finally {
            lock.unlock();
        }
    }

    int compare(T t, Node<T> l, Node<T> r, Node<T> curr) {
        if (curr == r) return -1;
        if (curr == l) return 1;
        return t.compareTo(curr.t);
    }

    static class Node<T extends Comparable<T>> {
        final T t;
        Node<T> next;

        public Node(T t) {
            this.t = t;
        }
    }
}
