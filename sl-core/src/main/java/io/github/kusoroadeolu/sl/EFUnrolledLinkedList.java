package io.github.kusoroadeolu.sl;

import io.github.kusoroadeolu.sl.EFUnrolledConcurrentList.ThreadNode;

import java.util.*;

import static io.github.kusoroadeolu.sl.UnrolledConcurrentList.*;

public class EFUnrolledLinkedList<T extends Comparable<T>> {
    private final UnrolledConcurrentList.Node<T> left;
    private final UnrolledConcurrentList.Node<T> right;
    private final int arrayCap;
    private final int minFull;
    private final int maxMerge;

    public EFUnrolledLinkedList(int arrCap, int minFull) {
        this.left = new UnrolledConcurrentList.SentinelNode<>();
        this.right = new UnrolledConcurrentList.SentinelNode<>();
        left.lock();
        try {
            left.next = right;
        }finally {
            left.unlock();
        }

        this.minFull = minFull;
        this.arrayCap = arrCap;
        maxMerge = (int) (0.75 * arrCap);
    }

    boolean add(ThreadNode<T> tn, EFUnrolledConcurrentList.LocalValues<T> localValues) {
        UnrolledConcurrentList.Node<T> l = left;
        UnrolledConcurrentList.Node<T> r = right;
        T t = tn.value;
        int aCap = arrayCap;
        var nodes = localValues.nodes();
        findNode(tn.value, l, r, nodes);
        var pred = nodes[0];
        var curr = nodes[1];

        if ((tn.node != null && curr != tn.node) || pred.loMarked() || curr.loMarked()) {
            tn.markAllRetry();
        }

        if (pred.tryLock()) {
            try {
                if (isNotValid(pred, curr)) {
                    tn.markAllRetry();
                    return false;
                }

                if (curr == r || t.compareTo(curr.anchor) < 0) {
                    UnrolledConcurrentList.Node<T> n = new UnrolledConcurrentList.Node<>(t, aCap);
                    n.soArray(0, t);
                    n.spNext(curr);
                    pred.soNext(n);
                    tn.markAllRetry();
                    return true;
                }

                List<ThreadNode<T>> validNodes = filterValidNodes(tn, curr ,localValues);
                int tnSize = validNodes.size();
                int size = curr.arraySize();
                int newSize = size + tnSize;

                if (newSize <= aCap) {
                    for (int i = 0, idx = 0; idx < tnSize; ++i) {
                        if (curr.lpArray(i) == null) {
                            var v = validNodes.get(idx++);
                            curr.soArray(i, v.value);
                            v.soFinished();
                        }

                    }

                    return true;
                } else { //Split
                    curr.lock(); //Lock to ensure no one can modify curr.next during the split
                    // So we have a consistent view of curr.next from when we start the split operation
                    try {
                        var succ = curr.lpNext();
                        var arr = curr.array;
                        split(arr, validNodes ,newSize ,nodes);
                        var n1 = nodes[0];
                        var n2 = nodes[1];

                        curr.soMarked();
                        n1.spNext(n2);
                        n2.spNext(succ);
                        pred.soNext(n1); //Linearization point
                        var h = tn.next;
                        while (h != null) {
                            h.soFinished();
                            h = h.next;
                        }

                        return true;
                    }finally {
                        curr.unlock();
                    }
                }

            }finally {
                pred.unlock();
            }
        } else return false;

    }

    void split(Object[] array, List<ThreadNode<T>> validNodes, int newSize ,Node<T>[] nodes) {
        Object[] copy = new Object[newSize];
        int idx = 0;
        for (Object o : array) {
            if (o != null) copy[idx++] = o;
        }

        for (int i = 0; i < validNodes.size(); ++i) {
            var h = validNodes.get(i);
            copy[idx++] = h.value;
            h.soFinished();
        }

        Arrays.sort(copy);
        Object[] arr1 = new Object[arrayCap];
        Object[] arr2 = new Object[arrayCap];

        int half = newSize / 2;
        int rem = newSize - half;
        System.arraycopy(copy, 0, arr1, 0, half);
        System.arraycopy(copy, half, arr2, 0, rem);
        nodes[0] = new Node<>(arr1);
        nodes[1] = new Node<>(arr2);

        // assert nodes[0].anchor.compareTo(pred.anchor) > 0 &&  nodes[1].anchor.compareTo(pred.anchor) > 0;
    }

    boolean remove(ThreadNode<T> tn, EFUnrolledConcurrentList.LocalValues<T> localValues) {
        UnrolledConcurrentList.Node<T> l = left;
        UnrolledConcurrentList.Node<T> r = right;
        int aCap = arrayCap;
        var nodes = localValues.nodes();
        findNode(tn.value, l, r, nodes);
        var pred = nodes[0];
        var curr = nodes[1];

        if ((tn.node != null && curr != tn.node) || pred.loMarked() || curr.loMarked()) {
            tn.markAllRetry();
        }

        if (curr == r || curr.anchor.compareTo(tn.value) > 0) {
            var h = tn;
            while (h != null) {
                if (currNode(h, localValues) == curr) h.soNotFound();
                else h.soRetry();
                h = h.next;
            }

            return true;
        }

        if (pred.tryLock()) {
            try {
                if (isNotValid(pred, curr)) return false;

                int size = curr.arraySize();

                int removeCount = removePresentValues(tn, localValues ,curr, aCap);
                int currSize = size - removeCount;

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
                        int succSize = succ.arraySize();
                        int total = currSize + succSize;
                        int[] emptyIndexes = new int[succSize];
                        findEmptyIndexes(emptyIndexes, aCap ,curr);
                        if (total <= maxMerge) { // Merge to fill the lower indices
                            merge(curr, succ, aCap ,emptyIndexes);
                        } else { //Redistribute so the lower index is not sparse
                            redistribute(curr, succ, succSize, aCap ,total);
                        }

                        return true;
                    }finally {
                        succ.unlock();
                    }

                }finally {
                    curr.unlock();
                }


            }finally {
                pred.unlock();
            }
        }

        return false;
    }

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

    boolean contains(Object o, EFUnrolledConcurrentList.LocalValues<T> localValues) {
        T t = (T) Objects.requireNonNull(o);
        var nodes = localValues.nodes();
        Node<T> curr;
        Node<T> l = left, r = right;

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

    List<ThreadNode<T>> filterValidNodes(ThreadNode<T> tn, Node<T> curr, EFUnrolledConcurrentList.LocalValues<T> localValues) {
        List<ThreadNode<T>> ls = new ArrayList<>();
        var h = tn;
        while (h != null) {
            if (h.value.compareTo(curr.anchor) < 0 || currNode(h, localValues) != curr) h.soRetry();
            else ls.add(h);

            h = h.next;
        }

        return ls;
    }

    int removePresentValues(ThreadNode<T> tn, EFUnrolledConcurrentList.LocalValues<T> lv, Node<T> curr, int arrayCap) {
        var h = tn;
        int removed = 0;
        while (h != null) {
            if (h.value.compareTo(curr.anchor) < 0 || currNode(h, lv) != curr) {
                h.soRetry();
                h = h.next;
                continue;
            }

            var v = h.value;
            boolean found = false;
            for (int i = 0; i < arrayCap; ++i) {
                var value = curr.lpArray(i);

                if (value != null && v.compareTo(value) == 0) {
                    curr.soArray(i, null);
                    ++removed;
                    h.soFinished();
                    found = true;
                    break;
                }
            }

            if (!found) h.soNotFound();
            h = h.next;
        }

        return removed;
    }

    Node<T> currNode(ThreadNode<T> t, EFUnrolledConcurrentList.LocalValues<T> localValues) {
        findNode(t.value, left, right, localValues.nodes());
        return localValues.nodes()[1];
    }

    static <T extends Comparable<T>>void merge(Node<T> curr, Node<T> succ, int arrayCap ,int[] indexes) {
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            T t = succ.lpArray(i);
            if (t != null) {
                var idx = indexes[j++];
                curr.soArray(idx, t);

            }
        }

        succ.soMarked();
        curr.soNext(succ.lpNext()); //Plain read for succ as we already hold its lock

    }

    static <T extends Comparable<T>>void redistribute(Node<T> curr, Node<T> succ, int succSize, int arrayCap ,int total) {
        Object[] copy = Arrays.stream(succ.array.clone())
                .filter(Objects::nonNull)
                .toArray();

        int nodeCount = total / 2;
        int toMove = succSize - nodeCount;
        Arrays.sort(copy);
        var nodeArr = Arrays.copyOf(copy, arrayCap);
        var node = new Node<>((T) nodeArr[toMove], nodeArr);
        for (int i = 0, j = 0; i < arrayCap; ++i) {
            if (j == toMove) break;
            if (curr.lpArray(i) == null) {
                curr.soArray(i, (T) nodeArr[j]);
                nodeArr[j++] = null;
            }
        }


        succ.soMarked();
        node.spNext(succ.lpNext());
        curr.soNext(node);
    }

    public List<T> anchorList() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        List<T> ls = new ArrayList<>();
        while (curr != r) {
            ls.add(curr.anchor);
            curr = curr.loNext();
        }

        return ls;
    }

    public Map<T, List<T>> nodeMap() {
        var l = left;
        var r = right;
        var curr = l.loNext();
        var map = new LinkedHashMap<T, List<T>>();
        while (curr != r) {
            List<T> ls = Arrays
                    .stream(curr.array)
                    .map(a -> (T) a)
                    .filter(Objects::nonNull)
                    .toList();

            map.put(curr.anchor, ls);
            curr = curr.loNext();
        }
        return map;
    }
}