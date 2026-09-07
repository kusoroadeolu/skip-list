package io.github.kusoroadeolu.sl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PCLinkedListTest {

    @Test
    void onFirstAdd_shouldInsertFirstElem() {
        PathCopyingSet<Integer> pclist = new PathCopyingSet<>();
        pclist.add(1);

        var ls = pclist.toList();
        assertEquals(1, ls.getFirst());
    }


    @Test
    void onAdd_inBetweenElems_assertContainsElem() {
        PathCopyingSet<Integer> pclist = new PathCopyingSet<>();
        pclist.add(1);
        pclist.add(2);
        pclist.add(4);
        pclist.add(3);


        assertTrue(pclist.contains(3));
    }

    @Test
    void onAdd_inBetweenElems_assertNotSameCopiedNodes() {
        PathCopyingSet<Integer> pclist = new PathCopyingSet<>();
        pclist.add(1);
        pclist.add(2);
        pclist.add(4);

        var fLs = pclist.toNodeList();

        pclist.add(3);

        var ls = pclist.toNodeList();

        assertNotSame(fLs.getFirst(), ls.getFirst());
        assertNotSame(fLs.get(1), ls.get(1));
    }

    @Test
    void onRemove_inBetweenElems_assertNotContainsElem() {
        PathCopyingSet<Integer> pclist = new PathCopyingSet<>();
        pclist.add(1);
        pclist.add(2);
        pclist.add(3);
        pclist.add(4);


        pclist.remove(3);

        assertFalse(pclist.contains(3));
    }

    @Test
    void onRemoveHead_assertNotContainsElem() {
        PathCopyingSet<Integer> pclist = new PathCopyingSet<>();
        pclist.add(1);

        pclist.remove(1);

        assertFalse(pclist.contains(1));
    }


    @Test
    void onRemove_inBetweenElems_assertNotSameCopiedNodes() {
        PathCopyingSet<Integer> pclist = new PathCopyingSet<>();
        pclist.add(1);
        pclist.add(2);
        pclist.add(3);
        pclist.add(4);

        var fLs = pclist.toNodeList();

        pclist.remove(3);

        var ls = pclist.toNodeList();

        assertNotSame(fLs.getFirst(), ls.getFirst());
        assertNotSame(fLs.get(1), ls.get(1));

        assertEquals(fLs.getFirst().item, ls.getFirst().item);
        assertEquals(fLs.get(1).item, ls.get(1).item);

    }
}