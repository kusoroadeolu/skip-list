package io.github.kusoroadeolu.sl;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentOrderedLinkedListTest {
    @Test
    void containsTest() {
        Lincheck.runConcurrentTest(() -> {
            var list = new ConcurrentOrderedLinkedList<Integer>();
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
            var list = new ConcurrentOrderedLinkedList<Integer>();
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
}