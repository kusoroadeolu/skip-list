package io.github.kusoroadeolu.sl;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

/*
* A linked list based on path copying. This linked list takes advantages of path copying during modifications to the
* list by replacing nodes being modified with new copies of those nodes to prevent race conditions.
*
* To prevent the issues of lost writes, we also replace the head of the list with a copy on modifications and treat that as
* our source of truth, therefore all other nodes are always reachable from the head of the list.
*
* This design highly favors reads under high contention as reads are wait free as their progress is never hindered by modifying threads.
* However, modifications which are lock free may incur extra overhead under contention as the creation of new immutable nodes from the head will put pressure on
* the GC and cas failures to the head may incur extra overhead
*
* This structure maintains the set invariant
*
* */
public class PathCopyingSet<T extends Comparable<T>> implements ConcurrentCollection<T> {

    private volatile Node<T> head;

    public PathCopyingSet() {
        head = null;
    }

    @Override
    public boolean add(T t) {
        Node<T> node = new Node<>(t);

        /*
        * A - B (we want to insert C here) - D
        * */
        outer: for (;;) {
            Node<T> head = this.head;

            if (containsFrom(head, t)) return false;

            node.next = null;

            if (head == null){
                if (casHead(null, node)) return true;
                else continue;
            }

            if (t.compareTo(head.item) < 0) {
                node.next = head;
                if (casHead(head, node)) return true;
                else continue;
            }

             //Stop one short before what we consider the actual curr, for example in our example, curr, stops at B rather than D

            Node<T> newHead = new Node<>(head.item, head.next);

            for (Node<T> pred = newHead;;) {
                Node<T> curr = pred.next;
                if (curr == null) {
                    pred.next = node;
                    if (casHead(head, newHead)) return true;
                    else continue outer;
                } else if ((t.compareTo(pred.item) > 0 && t.compareTo(curr.item) < 0)) {
                    pred.next = node;
                    node.next = curr;
                    if (casHead(head, newHead)) return true;
                    else continue outer;
                }

                var newCurr = new Node<>(curr.item, curr.next);
                pred.next = newCurr;
                pred = newCurr;
            }


        }
    }

    @Override
    public boolean remove(Object o) {
        T t = (T) o;
        outer: for (;;) {
            var head = this.head;
            boolean contains = containsFrom(head, t);
            if (!contains) return false;

            if (t.compareTo(head.item) == 0) {
                if (casHead(head, head.next)) return true;
                else continue;
            }

            Node<T> newHead = new Node<>(head.item, head.next);


            for (Node<T> pred = newHead;;) {
                Node<T> curr = pred.next;
                if (curr.item.compareTo(t) == 0) {
                    pred.next = curr.next;
                    if (casHead(head, newHead)) return true;
                    else continue outer;
                }

                var newCurr = new Node<>(curr.item, curr.next);
                pred.next = newCurr;
                pred = newCurr;
            }
        }
    }
    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return containsFrom(head, (T) o);
    }

    boolean containsFrom(Node<T> from, T t) {
        Node<T> h = from;

        if (h == null) return false;

        for (;;) {
            if (h == null) return false;
            int res = t.compareTo(h.item);
            if (res < 0) return false;
            else if (res == 0) return true;

            h = h.next;
        }
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public List<T> toList() {
        Node<T> h = head;

        if (h == null) return List.of();

        var ls = new ArrayList<T>();
        Node<T> node = h;

        for (;;) {
            if (node == null) return ls;
            else ls.add(node.item);
            node = node.next;
        }

    }


    List<Node<T>> toNodeList() {
        Node<T> h = head;

        if (h == null) return List.of();

        var ls = new ArrayList<Node<T>>();
        Node<T> node = h;

        for (;;) {
            if (node == null) return ls;
            else ls.add(node);
            node = node.next;
        }

    }

    boolean casHead(Node<T> seen, Node<T> newHead) {
        return HEAD.compareAndSet(this, seen, newHead);
    }

    static class Node<T extends Comparable<T>> {
        final T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }

        Node(T item, Node<T> next) {
            this.item = item;
            this.next = next;
        }
    }


    private static final VarHandle HEAD;

    static {
        try {
            HEAD = MethodHandles.lookup().findVarHandle(PathCopyingSet.class, "head", Node.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
