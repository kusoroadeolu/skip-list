package io.github.kusoroadeolu.sl;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

//A lock free ordered linked singly linked list set
// States - marked (linearization point for removal), null key (means the pred node has been logically fully deleted)
// While I'd build a skip list, the ordering of each level of a linked list in a skip list is the meatier problem, so i'll just save myself all the extra work and build the linked list instead
// The next node ideas were borrowed from fraser's linked thesis and the JDK Skip list map
// Ideally i'd make a skip list but the hardest part is in this linked list i built, you just have to use this linked list at every level honestly
public class ConcurrentOrderedLinkedList<T extends Comparable<T>> implements ConcurrentListSet<T>{
    private final Node<T> left;
    private final Node<T> right;
    private final LongAdder size;

    public ConcurrentOrderedLinkedList() {
        var ab = new AtomicBoolean(false);
        this.left = new LeftNode<>(ab);
        this.right = new RightNode<>(ab);
        left.next = right;
        size = new LongAdder();
    }

    // A - B - D
    // A - B - n - C - D
    // B = pred, C = curr
    //

    /*
    * We start iterating the set from the "left" node keeping pred and curr pointers
    * We iterate until pred.t < t && curr.t > t,
    * If curr.key == null, we restart from left
    * If curr.key == ours, we return false //We dont care too much about marked pointers, ideally, if we fail the cas, since we move forward, and we'll recheck if next is deleted
    * Otherwise we try cas pred.next -> our node
    * if that fails we set curr = pred.next
    * */
    public boolean add(T t) {
        Objects.requireNonNull(t);
        var l = left;
        var r = right;
        var node = new Node<>(t);
        outer: for (; ;) {
            var pred = l;
            var curr = pred.loNext();
            for (;;) {
                if (curr.t == null && curr != l && curr != r) continue outer;
                if (!curr.isMarked() && compare(t, curr, l, r) == 0) return false;
                if (compare(t, curr, l, r) > 0) {
                    pred = curr; curr = pred.loNext();
                } else {
                     //Ensure we immediately set curr = next; backed my volatile write
                    node.spNext(curr);
                    if (pred.casNext(curr, node)) break outer;
                    else curr = pred.loNext(); //Move backwards, don't change pred, two things could've happened, pred was deleted (it's next dummy next was marked) or a new node greater than pred was added
                }

            }
        }

        size.increment();
        return true;
    }

    //We iterate until we find t keeping track of pred, curr vars
    // If we reach value where curr = null, we restart from head
    //Otherwise, if curr == v, we try cas, if we fail the cas, we retry as another thread could've added an equal at that time
    public boolean remove(Object o) {
        T t = (T) o;
        Objects.requireNonNull(t);
        var l = left;
        var r = right;
        Node<T> seen = null;
        Node<T> next = null;
        outer: for (; ;) {
            var pred = l;
            var curr = pred.loNext();
            while (seen == null) {
                if (curr == r) return false;
                if (curr.t == null && curr != l) continue outer; //Restart from left
                if (compare(t, curr, l, r) == 0) { //We haven't seen a node yet
                    if (!curr.casMarked()) continue outer;  //We try and cas if not, continue to outer
                    else {
                        size.decrement();
                        Node<T> dummy = new Node<>(null, true); //Always set as marked
                        do {
                            next = curr.loNext();
                            dummy.spNext(next); //Backed by cas
                        } while (!curr.casNext(next, dummy));
                        seen = curr;

                        for (;;) {
                            if (pred.t == null && !isLeftOrRight(pred, l, r)) continue outer;
                            if (pred.casNext(curr, next)) return true;
                            pred = pred.loNext(); //Shift to new next
                        }
                    }

                }
                pred = curr; curr = pred.loNext();
            }


            // Helping unlink
            for (;;) {
                Node<T> seenCurr = curr;
                if (curr == r) return true;
                while (curr.isMarked()) { //Right is never marked so we stop at right worst case
                    curr = curr.loNext(); //Keep moving forward
                }

                if (!pred.casNext(seenCurr, curr)) {
                    Node<T> p = pred;
                    while (!(pred = p.loNext()).isMarked()){
                        if (pred == r) return true;
                        p = pred; //Move till pred
                    }

                    pred = p; //Set pred back to p
                    continue;
                }

                pred = curr; curr = pred.loNext();
            }

        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(Object o) {
        T t = (T) o;
        Objects.requireNonNull(t);
        var l = left;
        var r = right;
        outer: for (;;) {
            var pred = l;
            var curr = pred.loNext();
            for (;;) {
                if (curr == r) return false; //If we've reach the end of the list
                if (curr.t == null && curr != l) continue outer;
                if (!curr.isMarked() && compare(t, curr, l, r) == 0) return true;
                if (compare(t, curr, l, r) > 0) {
                    pred = curr;
                    curr = pred.loNext();
                } else return false;

            }
        }
    }

    public int size() {
        return size.intValue();
    }

    int compare(T t, Node<T> curr, Node<T> l, Node<T> r) {
        if (curr == r) return -1;       // right sentinel, stop
        if (curr == l) return 1;
        return t.compareTo(curr.t);
    }

    boolean isLeftOrRight(Node<T> node, Node<T> l, Node<T> r) {
       return isNode(node, l) || isNode(node, r);
    }

    boolean isNode(Node<T> node, Node<T> n) {
        return node == n;
    }


    private static class Node<T extends Comparable<T>> {
        private final T t;
        private final AtomicBoolean marked;
        private volatile Node<T> next;

        public Node(T t) {
            this.t = t;
            this.marked = new AtomicBoolean(false);
        }

        public Node(T t, boolean marked) {
            this.t = t;
            this.marked = new AtomicBoolean(marked);
        }

        public Node<T> loNext(){
            return (Node<T>) NEXT.getAcquire(this);
        }

        public boolean casNext(Node<T> seen, Node<T> ours) {
            return NEXT.compareAndSet(this, seen, ours);
        }

        public boolean isMarked(){
            return marked.getAcquire();
        }

        public void spNext(Node<T> next) {
            NEXT.set(this, next);
        }

        Node(T t,  AtomicBoolean marked , Node<T> next) {
            this.t = t;
            this.marked = marked;
            NEXT.set(this, next); //Backed by a volatile write
        }

        public boolean casMarked() {
            return marked.compareAndSet(false, true);
        }

        @Override
        public String toString() {
            return t.toString();
        }
    }

    //Left sentinel node
    private static class LeftNode<T extends Comparable<T>> extends Node<T> {


        public LeftNode(T t, AtomicBoolean m , Node<T> next) {
            super(t, m ,next);
        }

        public LeftNode(Node<T> next) {
            var b = new AtomicBoolean(true);
            this(null, b , next);
        }

        public LeftNode(AtomicBoolean b) {
            this(null, b ,null);
        }

        public void soNext(Node<T> next){
            NEXT.setRelease(this, next);
        }



        @Override
        public String toString() {
            return "LeftNode";
        }


    }

    private static class RightNode<T extends Comparable<T>> extends Node<T> {

        public RightNode(T t, AtomicBoolean b, Node<T> next) {
            super(t, b ,next);
        }

        public RightNode(Node<T> next) {
            var b = new AtomicBoolean(true);
            this(null, b, next);
        }

        public RightNode(AtomicBoolean b) {
            this(null, b, null);
        }

        @Override
        public String toString() {
            return "RightNode";
        }
    }

    @Override
    public String toString() {
        var l = left;
        var curr = l.loNext();
        var sb = new StringBuilder();
        while (curr != right) {
            sb.append(curr).append(" ");
            curr = curr.loNext();
        }
        return sb.toString();
    }

    private static final VarHandle NEXT;

    static {
        var l = MethodHandles.lookup();
        try {
            NEXT = l.findVarHandle(Node.class, "next", Node.class);
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void main() {
        var ls = new ConcurrentOrderedLinkedList<Integer>();
        ls.add(1);
        ls.add(2);
        ls.add(3);
        IO.println(ls);
        IO.println();
        ls.remove(1);
        IO.println(ls);
    }
}
