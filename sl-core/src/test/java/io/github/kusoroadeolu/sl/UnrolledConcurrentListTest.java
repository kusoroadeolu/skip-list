package io.github.kusoroadeolu.sl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sequential invariant tests for ConcurrentUnrolledSet.
 *
 * Invariants under test:
 *  I1 - Anchor ordering: each node's anchor > its predecessor's anchor
 *  I2 - Key placement: every key in a node >= that node's anchor
 *  I3 - No duplicates: adding the same key twice returns false and doesn't double-insert
 *  I4 - Size consistency: node.size == non-null elements in node.array
 *  I5 - No marked nodes in the live traversal
 *  I6 - Every key added (and not removed) is reachable via contains()
 *  I7 - Every key removed is no longer reachable via contains()
 */
class UnrolledConcurrentListTest {

    /**
     * I1: anchors must be strictly ascending across the live node list.
     */
    static <T extends Comparable<T>> void assertAnchorOrdering(ConcurrentUnrolledSet<T> list) {
        List<T> anchors = list.anchorList();
        for (int i = 1; i < anchors.size(); i++) {
            T prev = anchors.get(i - 1);
            T curr = anchors.get(i);
            assertTrue(prev.compareTo(curr) < 0,
                    "I1 violated: anchor[" + (i - 1) + "]=" + prev + " >= anchor[" + i + "]=" + curr);
        }
    }

    /**
     * I2: every key inside a node must be >= that node's anchor.
     */
    static <T extends Comparable<T>> void assertKeyPlacement(ConcurrentUnrolledSet<T> list) {
        Map<T, List<T>> nodeMap = list.nodeMap();
        for (Map.Entry<T, List<T>> e : nodeMap.entrySet()) {
            T anchor = e.getKey();
            for (T key : e.getValue()) {
                assertTrue(key.compareTo(anchor) >= 0,
                        "I2 violated: key " + key + " < anchor " + anchor + " in its own node");
            }
        }
    }

    /**
     * I4: node.size must equal the count of non-null slots in the array.
     * We infer this from nodeMap() — the list size it returns is the non-null count.
     * We expose size via a thin accessor added below (see note).
     *
     * Because ConcurrentUnrolledSet doesn't expose node.size directly through its
     * public API we verify the weaker property: nodeMap value list size == what
     * anchorList / nodeMap says (i.e. no phantom entries).
     */
    static <T extends Comparable<T>> void assertNoDuplicatesWithinNode(ConcurrentUnrolledSet<T> list) {
        Map<T, List<T>> nodeMap = list.nodeMap();
        for (Map.Entry<T, List<T>> e : nodeMap.entrySet()) {
            List<T> keys = e.getValue();
            Set<T> unique = new HashSet<>(keys);
            assertEquals(
                    unique.size(),
                    keys.size(),
                    "I4/I3 violated: duplicate keys inside node anchored at " + e.getKey() + ": " + keys
            );
        }
    }

    /** Convenience — run all structural invariants in one call. */
    static <T extends Comparable<T>> void assertAllInvariants(ConcurrentUnrolledSet<T> list) {
        assertAnchorOrdering(list);
        assertKeyPlacement(list);
        assertNoDuplicatesWithinNode(list);
    }

    // -----------------------------------------------------------------------
    // I3 — Duplicate / set semantics
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("I3 – No duplicates (set semantics)")
    class DuplicateTests {

        @Test
        @DisplayName("Adding same element twice returns false on second add")
        void addDuplicateReturnsFalse() {
            var list = new ConcurrentUnrolledSet<Integer>();
            assertTrue(list.add(42));
            assertFalse(list.add(42), "second add of same key should return false");
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Duplicate not physically stored twice in node")
        void duplicateNotStoredTwice() {
            var list = new ConcurrentUnrolledSet<Integer>(8, 2);
            list.add(10);
            list.add(10);
            Map<Integer, List<Integer>> map = list.nodeMap();
            long count = map.values().stream().flatMap(Collection::stream)
                    .filter(v -> v == 10).count();
            assertEquals(1, count, "key 10 should appear exactly once across all nodes");
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Add-remove-readd is allowed and returns true")
        void addRemoveReadd() {
            var list = new ConcurrentUnrolledSet<Integer>();
            list.add(5);
            list.remove(5);
            assertTrue(list.add(5), "re-adding after remove should succeed");
            assertTrue(list.contains(5));
            assertAllInvariants(list);
        }
    }

    // -----------------------------------------------------------------------
    // I1 + I2 — Structural / ordering invariants
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("I1/I2 – Anchor ordering and key placement")
    class StructuralTests {

        @Test
        @DisplayName("Ascending inserts keep anchors ordered")
        void ascendingInserts() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            IntStream.rangeClosed(1, 20).forEach(list::add);
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Descending inserts keep anchors ordered")
        void descendingInserts() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            IntStream.iterate(20, i -> i - 1).limit(20).forEach(list::add);
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Random-order inserts keep anchors ordered")
        void randomInserts() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            List<Integer> vals = new ArrayList<>(IntStream.rangeClosed(1, 30).boxed().toList());
            Collections.shuffle(vals, new Random(0xDEADBEEF));
            vals.forEach(list::add);
            System.out.println(list.nodeMap());
            assertAllInvariants(list);
        }

        @ParameterizedTest(name = "arrayCap={0}")
        @ValueSource(ints = {2, 4, 8, 16})
        @DisplayName("Invariants hold across different array capacities")
        void differentCapacities(int cap) {
            var list = new ConcurrentUnrolledSet<Integer>(cap, cap / 2);
            IntStream.rangeClosed(1, cap * 3).forEach(list::add);
            assertAllInvariants(list);
        }
    }

    // -----------------------------------------------------------------------
    // I1 + I2 after splits
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Split correctness")
    class SplitTests {

        @Test
        @DisplayName("Splitting a full node produces two nodes with correct anchors")
        void splitProducesOrderedAnchors() {
            // cap=4 so adding 5 elements triggers a split
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            for (int i = 0; i < 5; i++) list.add(i);

            List<Integer> anchors = list.anchorList();
            assertEquals(2, anchors.size(), "a split should produce exactly 2 nodes from 1");
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Keys land in the correct half after split")
        void keysInCorrectHalfAfterSplit() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            // Fill node then overflow to trigger split
            for (int i : new int[]{10, 20, 30, 40, 50}) list.add(i);

            assertKeyPlacement(list);
            // Every key must still be findable
            for (int i : new int[]{10, 20, 30, 40, 50}) {
                assertTrue(list.contains(i), "lost key after split: " + i);
            }
        }

        @Test
        @DisplayName("Multiple consecutive splits keep all keys reachable")
        void multiSplitKeyRetention() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            List<Integer> inserted = IntStream.rangeClosed(1, 40).boxed().toList();
            inserted.forEach(list::add);
            System.out.println(list.nodeMap());

            assertAllInvariants(list);
            for (int v : inserted) {
                assertTrue(list.contains(v), "key " + v + " lost after splits");
            }
        }
    }

    // -----------------------------------------------------------------------
    // I5 — No marked nodes survive in the live list
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("I5 – No marked nodes in live list")
    class MarkedNodeTests {

        @Test
        @DisplayName("Contains returns false for all removed keys")
        void containsFalseAfterRemove() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            List<Integer> vals = List.of(5, 10, 15, 20, 25, 30);
            vals.forEach(list::add);
            vals.forEach(list::remove);

            for (int v : vals) {
                assertFalse(list.contains(v), "contains should be false after remove: " + v);
            }
        }
    }

    // -----------------------------------------------------------------------
    // I6/I7 — Reachability after mixed add/remove sequences
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("I6/I7 – Key reachability after mixed operations")
    class ReachabilityTests {

        @Test
        @DisplayName("Interleaved add/remove — surviving keys are reachable")
        void interleavedAddRemove() {
            var list = new ConcurrentUnrolledSet<Integer>();
            Set<Integer> alive = new HashSet<>();

            for (int i = 1; i <= 30; i++) {
                list.add(i);
                alive.add(i);
            }

            for (int i = 1; i <= 30; i += 2) { // remove odds
                list.remove(i);
                alive.remove(i);
            }


            assertAllInvariants(list);
            for (int v : alive) {
                assertTrue(list.contains(v), "surviving key not found: " + v);
            }
            for (int i = 1; i <= 30; i += 2) {
                assertFalse(list.contains(i), "removed key still found: " + i);
            }
        }

        @Test
        @DisplayName("Remove on empty list returns false without throwing")
        void removeFromEmpty() {
            var list = new ConcurrentUnrolledSet<Integer>();
            assertFalse(list.remove(99));
        }

        @Test
        @DisplayName("Remove non-existent key returns false")
        void removeNonExistent() {
            var list = new ConcurrentUnrolledSet<Integer>();
            list.add(1);
            list.add(2);
            assertFalse(list.remove(999));
            assertAllInvariants(list);
        }

        @Test
        @DisplayName("Contains on empty list returns false")
        void containsOnEmpty() {
            var list = new ConcurrentUnrolledSet<Integer>();
            assertFalse(list.contains(42));
        }

        @Test
        @DisplayName("Large mixed workload preserves all invariants")
        void largeMixedWorkload() {
            var list = new ConcurrentUnrolledSet<Integer>(8, 3);
            Random rng = new Random(42);
            Set<Integer> alive = new HashSet<>();

            for (int round = 0; round < 500; round++) {
                int v = rng.nextInt(200);
                if (rng.nextBoolean()) {
                    boolean added = list.add(v);
                    if (added) alive.add(v);
                } else {
                    boolean removed = list.remove(v);
                    if (removed) alive.remove(v);
                }
            }

            assertAllInvariants(list);
            for (int v : alive) {
                assertTrue(list.contains(v), "alive key missing: " + v);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Merge / redistribute correctness
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Merge and redistribute correctness")
    class MergeTests {

        @Test
        @DisplayName("Merge keeps all surviving keys reachable")
        void mergeKeysReachable() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            // Fill two nodes, then drain the first below minFull to trigger merge
            for (int i = 1; i <= 8; i++) list.add(i);
            // Remove 3 of the first 4 elements → size drops to 1, below minFull=2
            list.remove(1);
            list.remove(2);
            list.remove(3);

            assertAllInvariants(list);
            // 4–8 should still be present
            for (int i = 4; i <= 8; i++) {
                assertTrue(list.contains(i), "key " + i + " lost during merge");
            }
        }

        @Test
        @DisplayName("Redistribute keeps all keys reachable")
        void redistributeKeysReachable() {
            // To trigger redistribute we need curr.size + succ.size >= arrayCap
            // Use cap=4, minFull=2. Fill to 8 → 2 nodes of 4. Remove 2 from first node → size=2=minFull,
            // succ.size=4, total=6 >= 4=arrayCap, so redistribute fires instead of merge.
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            for (int i = 1; i <= 8; i++) list.add(i);
            list.remove(1);
            list.remove(2);

            assertAllInvariants(list);
            for (int i = 3; i <= 8; i++) {
                assertTrue(list.contains(i), "key " + i + " lost during redistribute");
            }
        }
    }

    @Nested
    @DisplayName("Edge cases in merge/redistribute")
    class MergeEdgeCases {

        @Test
        @DisplayName("Merge when succ.size is zero crashes findEmptyIndexes")
        void mergeWithZeroSizedSucc() {
            var list = new ConcurrentUnrolledSet<Integer>(4, 2);
            // Build two nodes: [1,2,3,4] and [5,6,7,8]
            for (int i = 1; i <= 8; i++) list.add(i);

            // Drain succ completely first (without triggering its own merge/unlink)
            // then drain curr below minFull so it tries to merge into an empty succ
            list.remove(5);
            list.remove(6);
            list.remove(7);
            list.remove(8); // succ.size = 0 but may still be linked
            list.remove(3); // curr.size drops to minFull, tries to merge with zombie succ
            list.remove(4);

            assertAllInvariants(list);
        }
    }

    @Nested
    class NullStress {
        @Test
        void testChurnedSplit() {
            ConcurrentUnrolledSet<Integer> list = new ConcurrentUnrolledSet<>();
            Random rng = new Random(42);

            // Churn within a small key range to replicate benchmark conditions
            for (int i = 0; i < 1_000_000; i++) {
                int key = rng.nextInt(200); // small range = high collision = heavy churn per node
                int op = rng.nextInt(100);
                if (op < 50) {
                    assertDoesNotThrow(() -> list.add(key));
                } else if (op < 90) {
                    list.remove(key);
                } else {
                    list.contains(key);
                }
            }

            System.out.println("Node: " + list.nodeMap());
        }
    }
}

