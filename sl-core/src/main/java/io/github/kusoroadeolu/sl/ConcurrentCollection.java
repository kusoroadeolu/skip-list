package io.github.kusoroadeolu.sl;

import java.util.List;

public interface ConcurrentCollection<T> {
    boolean add(T t);

    boolean remove(Object t);

    boolean isEmpty();

    boolean contains(Object t);

    int size();

    List<T> toList();

    default void clear() {
        List<T> ls = toList();
        for (T t : ls) {
            remove(t);
        }

        ls.clear();
    }

}
