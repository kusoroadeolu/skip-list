package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;

public class ListStress {

    @JCStressTest
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class UniqueInvariantStress {
        public final ConcurrentListSet<Integer> set;

        public UniqueInvariantStress() {
            this.set = new ConcurrentOrderedLinkedList<>();
        }


        @Actor
        public void actor() {
            set.add(1);
        }

        @Actor
        public void actor1() {
            set.add(1);
        }

        @Actor
        public void actor2() {
            set.add(1);
        }


        @Arbiter
        public void arbiter(I_Result r) {
            r.r1 = set.size();
        }
    }

    @JCStressTest
    @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class RemoveInvariantStress {
        public final ConcurrentListSet<Integer> set;

        public RemoveInvariantStress() {
            this.set = new ConcurrentOrderedLinkedList<>();
        }


        @Actor
        public void actor() {
            set.add(1);
        }

        @Actor
        public void actor1() {
            set.add(2);
        }

        @Actor
        public void actor2() {
            set.add(3);
        }

        @Actor
        public void actor3() {
            set.add(2);
        }


        @Arbiter
        public void arbiter(I_Result r) {
            if (set.size() == 3 && set.contains(2)) { //If remove ran before add
                r.r1 = 1;
            }else if (set.size() == 2 && !set.contains(2)){
                r.r1 = 1; //W
            }
        }
    }

}
