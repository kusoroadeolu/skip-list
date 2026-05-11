package io.github.kusoroadeolu.sl;


import java.util.*;


/// ## SkipListSet
///
/// An ordered, unbounded skip list implementation of the [Set] interface.
/// All elements must implement [Comparable] and are maintained in ascending natural order.
/// Duplicate elements are not permitted, consistent with [Set] invariants.
///
/// ## Implementation
///
/// Internally, this structure maintains a tower of doubly linked node lists spanning
/// levels `0` through `h-1`, where level `0` is the base list containing all elements.
/// Higher levels act as express lanes, allowing traversal to skip over large portions
/// of the list.
///
/// A sentinel head and tail node spans all levels and serves as the fixed entry point for
/// traversal, eliminating boundary edge cases during search and mutation. Each node
/// holds an array of forward pointers, one per level it participates in, enabling
/// efficient multi-level linking and unlinking during [Set#add] and [Set#remove] operations.
///
/// Doubly linked pointers are maintained to simplify node removal, avoiding the need
/// to track a separate predecessor reference during unlinking.
///
/// ## Performance
///
/// | Operation   | Expected    |
/// |-------------|-------------|
/// | `add`       | O(n)        |
/// | `remove`    | O(log n)    |
/// | `contains`  | O(log n)    |
///
/// @param <T> the type of elements held in this set; must implement [Comparable]
///
///
/// [PUGH](https://15721.courses.cs.cmu.edu/spring2016/papers/pugh-skiplists1990.pdf)

@SuppressWarnings("unchecked")
public class SkipListSet<T extends Comparable<T>> implements Set<T> {
    private final Node<T> head;
    private final int height;
    private final Random random;
    private int size;
    private final double probability;
    private static final int MAX_HEIGHT = 32;
    private static final double DEFAULT_PROBABILITY = 0.25;

    public SkipListSet(int height) {
        this(height, DEFAULT_PROBABILITY);
    }

    public SkipListSet(int height, double probability) {
        if (height < 0) throw new IllegalArgumentException("height < 0");
        if (probability < 0) throw new IllegalArgumentException("probability < 0");
        if (probability >= 1) throw new IllegalArgumentException("probability >= 0");
        this.height = Math.min(height, MAX_HEIGHT);
        this.head = fillHead(this.height);
        this.random = new Random();
        this.probability = probability;
    }

    void link(Node<T> curr, Node<T> node) {
        var prev = curr.prev();
        prev.setNext(node);
        curr.setPrev(node);
        node.setNext(curr);
        node.setPrev(prev);
    }


    /*
    * L1: 1,2,3,5,6
    * L2: 1,3,5
    * L3: 5
    * L4: 4
    * L5:
    * */

    @Override
    public boolean add(T t) {
        Objects.requireNonNull(t);
        int maxLevel = generateLevel();
        Node<T>[] hs = this.head.nodes();
        Node<T>[] ns = fillTo(t, maxLevel).nodes();
        Node<T> curr = null;
        outer: for (int i = 0; i < maxLevel; ++i) {
            if (curr == null) curr = hs[i];

            Node<T> node = ns[i];
            while (curr != null) {
                var next = curr.next();
                T v = curr.value;
                if (Objects.equals(t, v)) return false;

                if (v != null && t.compareTo(v) <= 0) {
                     link(curr, node);
                     int nextIdx = i + 1;
                     if (curr.height <= nextIdx) curr = null;
                     else curr = curr.nodes()[nextIdx];

                     continue outer;
                }else if(next == null) {
                    curr.setNext(node);
                    node.setPrev(curr);
                    int nextIdx = i + 1;
                    if (curr.height <= nextIdx) curr = null;
                    else curr = curr.nodes()[nextIdx];
                    continue outer;
                }

                curr = next;
            }

        }

        ++size;
        return true;
    }



    //Start from the bottom level
    @Override
    public boolean remove(Object o) {
        if (size == 0) return false;
        Node<T> node = findNode(o);
        if (node == null) return false;

        --size;
        var arr = node.nodes();

        for (Node<T> tNode : arr) {
            detachNode(tNode);
        }

        return true;
    }

    @Override
    public boolean contains(Object o) {
        if (size == 0) return false;
        var node = findNode(o);
        return node != null;
    }

    @Override
    public int size() {
        return size;
    }

    void detachNode(Node<T> node) {
        var prev = node.prev();
        var next = node.next();
        prev.setNext(next);
        if (next != null) next.setPrev(prev);
        nullPointers(node);
    }

    void nullPointers(Node<T> node) {
        node.setPrev(null);
        node.setNext(null);
    }

    Node<T> findNode(Object o) {
        T t = (T) o;
        int level = height - 1;
        Node<T> curr = head.nodes()[level];
        while (curr != null) {
            if (Objects.equals(t, curr.value)) return curr;

            var next = curr.next();
            if ((next == null || next.value.compareTo(t) > 0) && level > 0) { //Jump to the lower level if the next value is > t, so we don't miss t
                curr = curr.nodes()[--level];
                continue;
            }

            curr = next;
        }

        return curr;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public Object[] toArray() {
        if (size == 0) return new Object[0];
        Object[] o = new Object[size];
        Node<T> curr =  head.nodes()[0].next();
        int i = 0;
        while (curr != null) {
            o[i++] = curr.value;
            curr = curr.next();
        }

        return o;
    }

    @Override
    public <T1> T1[] toArray(T1[] a) {
        if (size == 0) return a;
        int len = a.length;
        if (size > len) a = Arrays.copyOf(a, size);
        int i = 0;
        Node<T> curr =  head.nodes()[0].next();
        while (curr != null) {
            a[i++] = (T1) curr.value;
            curr = curr.next();
        }

        return a;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) if (!contains(o)) return false;
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        int count = 0;
        for (T t : c) {
             if(add(t)) count++;
        }

        return count > 0;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        if (size == 0) return false;
        var set = new HashSet<>(c);
        Node<T> curr = head.nodes()[0].next();
        boolean all = true;

        while (curr != null) {
            var next = curr.next(); //Store next before trying to remove curr
            var v = curr.value;
            if (!set.contains(v)) {
                 if(!remove(v)) all = false;
            }
            curr = next;
        }

        return all;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean all = true;
        for (Object o : c) {
            if (size == 0) return all;
            if (!remove(o)) all = false;
        }
        return all;
    }


    @Override
    public void clear() {
        if (size == 0) return;
        Node<T>[] ns = head.nodes();
        for (Node<T> n : ns) {
            n.setNext(null);
        }

        size = 0;
    }



    @Override
    public Iterator<T> iterator() {
        return new ListIterator<>(this);
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < height; ++i) {
            sb.append("Level ").append(i).append(": ");
            Node<T> curr = head.nodes()[i];
            while (curr != null) {
                if (curr.value == null) {
                    curr = curr.next();
                    continue;
                }

                sb.append(curr.value);
                var next = curr.next();
                if (next != null) sb.append(" -> ");
                curr = next;

            }

            sb.append("\n");
        }

        return sb.toString();
    }

     Node<T> fillTo(T value, int height) {
        Node<T>[] nodes = new Node[height];
        //assert height > 0;
        for (int i = 0; i < height; ++i) {
            nodes[i] = new Node<>(value, height ,nodes);
        }

        return nodes[0];
    }

    Node<T> fillHead(int height) {
        HeadNode<T>[] nodes = new HeadNode[height];
        for (int i = 0; i < height; ++i) {
            nodes[i] = new HeadNode<>(null, height ,nodes);
        }

        return nodes[0];
    }

    private static class ListIterator<T extends Comparable<T>> implements Iterator<T> {

        private final ArrayList<T> list;
        private int i;

        public ListIterator(SkipListSet<T> set) {
            list = new ArrayList<>(set.size);
            var n = set.head.next();
            while (n != null) {
                list.add(n.value);
                n = n.next();
            }
        }

        @Override
        public boolean hasNext() {
            return i < list.size();
        }

        @Override
        public T next() {
            return list.get(i++);
        }


    }

    private static class Node<T extends Comparable<T>> {
        Node<T>[] nodes;
        Node<T> next; //Next of the node on this level
        Node<T> prev; //Prev of the node on this level
        final T value;
        final int height;

        public Node(T value, int height ,Node<T>[] nodes) {
            this.nodes = nodes;
            this.height = height;
            this.value = value;
        }

        public Node<T> prev() {
            return prev;
        }

        public Node<T> next() {
            return next;
        }

        public Node<T>[] nodes() {
            return nodes;
        }

        public void setNext(Node<T> next) {
            this.next = next;
        }

        public void setPrev(Node<T> prev) {
            this.prev = prev;
        }
    }

    private int generateLevel() {
        double r = random.nextDouble();
        int level = (int)(Math.log(r) / Math.log(probability)) + 1;
        return Math.min(level, height);
    }

    static class HeadNode<T extends Comparable<T>> extends Node<T> {

        public HeadNode(T value, int height ,Node<T>[] nodes) {
            super(value, height, nodes);
        }

        @Override
        public void setPrev(Node<T> prev) {
            throw new UnsupportedOperationException("head#prev == null");
        }

    }




    static void main() {
        var set = new SkipListSet<Integer>(5);
        for (int i = 10; i > 0; --i) set.add(i);
        IO.println(set);
    }
}
