package io.github.kusoroadeolu.sl;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;


// A - B - C - D - E
// A - B - C - D(deleted) - E
class ConcurrentOrderedLinkedListTest {
    @Test
    void containsTest() {
        Lincheck.runConcurrentTest(() -> {
            var list = new ConcurrentOrderedSet<Integer>();
            Thread t1 = new Thread(() -> list.add(1));
            Thread t2 = new Thread(() -> list.add(2));
            Thread t3 = new Thread(() -> list.add(3));

            t1.start(); t2.start(); t3.start();

            try {
                t1.join();
                t2.join();
                t3.join();
            }catch (Exception _) {

            }

            assertTrue(list.contains(1));
            assertTrue(list.contains(2));
            assertTrue(list.contains(3));
        });
    }


    @Test
    void removeTest() {
        Lincheck.runConcurrentTest(() -> {
            var list = new ConcurrentOrderedSet<Integer>();
            Thread t1 = new Thread(() -> list.add(1));
            Thread t2 = new Thread(() -> list.add(2));
            Thread t3 = new Thread(() -> list.add(3));
            Thread t4 = new Thread(() -> list.remove(2));
            t1.start(); t2.start(); t3.start(); t4.start();


            try {
                t1.join();
                t2.join();
                t3.join();
                t4.join();
            }catch (Exception _) {

            }

            int size = list.size();
            if (size != 3) assertFalse(list.contains(2));

        });
    }

    @Test
    void correctlyOrdered() {
        Lincheck.runConcurrentTest(() -> {
            var list = new ConcurrentOrderedSet<Integer>();
            Thread t1 = new Thread(() -> list.add(1));
            Thread t2 = new Thread(() -> list.add(2));
            Thread t3 = new Thread(() -> list.add(3));
            t1.start(); t2.start(); t3.start();


            try {
                t1.join();
                t2.join();
                t3.join();
            }catch (Exception _) {

            }
            var ls = list.toList();
            assertEquals(1, ls.getFirst());
            assertEquals(2, ls.get(1));
            assertEquals(3, ls.getLast());

        });
    }

    @Test
    void concurrentRemoves() {
        Lincheck.runConcurrentTest(() -> {
            var list = new ConcurrentOrderedSet<Integer>();

            Thread t1 = new Thread(() -> {
                list.add(1);
                list.add(3);
                list.remove(5);
            });
            Thread t2 = new Thread(() -> {
                list.add(2);
                list.remove(1);
            });
            Thread t3 = new Thread(() -> {
                list.remove(2);
                list.add(5);
            });

            t1.start(); t2.start(); t3.start();

            try {
                t1.join(); t2.join(); t3.join();
                var ls = list.toList();

                boolean isSorted = IntStream.range(0, ls.size() - 1)
                        .allMatch(i -> ls.get(i) <= ls.get(i + 1));

                assertTrue(isSorted);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }



}