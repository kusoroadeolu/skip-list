package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

public class UnrolledListStress {
    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class OrderedAnchorStress {
        public ConcurrentUnrolledSet<Integer> set;
        final int bound = 20;

        public OrderedAnchorStress() {
            this.set = new ConcurrentUnrolledSet<>(4, 1);
        }


        @Actor
        public void actor() {
            doWork();
        }

        @Actor
        public void actor2() {
            doWork();
        }

        @Actor
        public void actor3() {
            doWork();
        }

        void doWork() {
            for (int i = 0; i <= 5; ++i) {
                boolean add = ThreadLocalRandom.current().nextInt() % 2 == 0;
                set.add(ThreadLocalRandom.current().nextInt(bound));
            }
        }



        @Arbiter
        public void arbiter(I_Result r) {
            var ls = set.anchorList();
            boolean isSorted = IntStream.range(0, ls.size() - 1)
                    .allMatch(i -> ls.get(i) <= ls.get(i + 1));
            r.r1 = isSorted ? 1 : 0;
            if(!isSorted) {
                System.out.println(set.nodeMap());
                System.out.println();
            }

            var lsn = set.toList();
            for (int i : lsn) set.remove(i);
        }
    }


    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class AnchorInvariantStress {
        public ConcurrentUnrolledSet<Integer> set;
        final int bound = 20;
        public AnchorInvariantStress() {
            this.set = new ConcurrentUnrolledSet<>(4, 1);
        }


        @Actor
        public void actor() {
            doWork();
        }

        @Actor
        public void actor2() {
            doWork();
        }

        @Actor
        public void actor3() {
            doWork();
        }

        void doWork() {
            for (int i = 0; i <= 5; ++i) {
                boolean add = ThreadLocalRandom.current().nextInt() % 2 == 0;
                if (add) {
                    set.add(ThreadLocalRandom.current().nextInt(bound));
                }else set.remove(ThreadLocalRandom.current().nextInt(bound));
            }
        }



        @Arbiter
        public void arbiter(I_Result r) {
            var map = set.nodeMap();
            boolean isLess = true;

            for(Map.Entry<Integer, List<Integer>> entry : map.entrySet()) {
                var anchor = entry.getKey();
                var ls = entry.getValue();
                for (int i : ls) {
                    if (i < anchor) {
                        isLess = false;
                        break;
                    }
                }

            }

            r.r1 = isLess ? 1 : 0;
        }
    }
}
