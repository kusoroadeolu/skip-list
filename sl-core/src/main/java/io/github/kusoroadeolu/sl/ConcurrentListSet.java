package io.github.kusoroadeolu.sl;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

public interface ConcurrentListSet<T> extends Set<T> {
    boolean add(T t);

    boolean remove(Object t);

    boolean isEmpty();

    boolean contains(Object t);

    int size();

    @Override
    default Iterator<T> iterator() {
        return null;
    }

    @Override
    default Object[] toArray() {
        return new Object[0];
    }

    @Override
    default <T1> T1[] toArray(T1[] a) {
        return null;
    }

    @Override
    default boolean containsAll(Collection<?> c) {
        return false;
    }

    @Override
    default boolean addAll(Collection<? extends T> c) {
        return false;
    }

    @Override
    default boolean retainAll(Collection<?> c) {
        return false;
    }

    @Override
    default boolean removeAll(Collection<?> c) {
        return false;
    }

    @Override
    default void clear() {

    }

}
