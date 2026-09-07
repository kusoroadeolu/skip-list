package io.github.kusoroadeolu.sl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;

import static io.github.kusoroadeolu.sl.ConcurrentEliminationUnrolledSet.free;


public class EliminationNode<T extends Comparable<T>> {
    static final int NCPU = Runtime.getRuntime().availableProcessors();
    final AtomicReferenceArray<ThreadInfo<T>> arena;

    public final T anchor;
    public final Object[] array;
    final Lock lock;
    volatile boolean marked;
    volatile EliminationNode<T> next;
    int size;

    public EliminationNode(T anchor, int capacity) {
        this.anchor = anchor;
        this.array = new Object[capacity];
        this.lock = new SpinLock();
        arena = fillArena();
    }

    public EliminationNode(T anchor, int capacity, AtomicReferenceArray<ThreadInfo<T>> arena) {
        this.anchor = anchor;
        this.array = new Object[capacity];
        this.lock = new SpinLock();
        this.arena = arena;
    }

    public EliminationNode(Object[] initialArray) {
        this.anchor = (T) initialArray[0];
        this.array = initialArray;
        this.lock = new SpinLock();
        arena = fillArena();

    }

    public EliminationNode(T anchor, Object[] array) {
        this.anchor = anchor;
        this.array = array;
        this.lock = new SpinLock();
        arena = fillArena();
    }

    void lock() {
        lock.lock();
    }

    boolean tryLock() {
        return lock.tryLock();
    }

    void unlock() {
        lock.unlock();
    }

    void spArray(int idx, T t) {
        ARRAY.set(array, idx, t);
    }

    void soArray(int idx, T t) {
        ARRAY.setRelease(array, idx, t);
    }

    T loArray(int idx) {
        return (T) ARRAY.getAcquire(array, idx);
    }

    T lpArray(int idx) {
        return (T) ARRAY.get(array, idx);
    }

    void soNext(EliminationNode<T> node) {
        NEXT.setRelease(this, node);
    }

    EliminationNode<T> lpNext() {
        return (EliminationNode<T>) NEXT.get(this);
    }

    boolean loMarked(){
        return (boolean) MARKED.getAcquire(this);
    }

    boolean lopMarked() {
        return (boolean) MARKED.getOpaque(this);
    }

    boolean lpMarked(){
        return (boolean) MARKED.get(this);
    }

    void soMarked(){
        MARKED.setRelease(this, true);
    }

    public EliminationNode<T> loNext() {
        return (EliminationNode<T>) NEXT.getAcquire(this);
    }

    public void spNext(EliminationNode<T> node) {
        NEXT.set(this, node);
    }

    @Override
    public String toString() {
        return anchor + " : " + Arrays.toString(array) + " -> " + next;
    }

    public String asString() {
        return anchor + " : " + Arrays.toString(array);
    }

    void increment(int by) {
        SIZE.getAndAddRelease(this, by);
    }

    void decrement() {
        SIZE.getAndAddRelease(this, -1);
    }

    int size() {
        return (int) SIZE.getAcquire(this);
    }

    static <T extends Comparable<T>> AtomicReferenceArray<ThreadInfo<T>> fillArena() {
        AtomicReferenceArray<ThreadInfo<T>> arena = new AtomicReferenceArray<>(NCPU/2);
        for (int i = 0; i < arena.length(); ++i) {
            arena.setRelease(i, free());
        }
        return arena;
    }

    private static final VarHandle MARKED;
    private static final VarHandle NEXT;
    private static final VarHandle ARRAY;
    private static final VarHandle SIZE;

    static {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
            MARKED = l.findVarHandle(EliminationNode.class, "marked", boolean.class);
            NEXT = l.findVarHandle(EliminationNode.class, "next", EliminationNode.class);
            SIZE = l.findVarHandle(EliminationNode.class, "size", int.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int lpSize() {
        return (int) SIZE.get(this);
    }

    public record ThreadInfo<T> (T value, ConcurrentUnrolledSet.Operation op){}

    static class SentinelEliminationNode<T extends Comparable<T>> extends EliminationNode<T> {
        public SentinelEliminationNode() {
            super(null, 0, fillArena());
        }
    }
}