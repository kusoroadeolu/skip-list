package io.github.kusoroadeolu.sl;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.I_Result;
import org.openjdk.jcstress.infra.results.Z_Result;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

public class ListStress {

    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class UniqueInvariantStress {
        public final ConcurrentCollection<Integer> set;

        public UniqueInvariantStress() {
            this.set = new ConcurrentOrderedSet<>();
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
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class AddRemoveTest {
        public ConcurrentCollection<Integer> set;

        public AddRemoveTest() {
            this.set = new ConcurrentOrderedSet<>();
            set.add(1);
            set.add(2);
            set.add(3);
        }


        @Actor
        public void actor() {
            set.remove(1);
        }

        @Actor
        public void actor1() {
            set.remove(2);
        }



        @Arbiter
        public void arbiter(I_Result r) {
            r.r1 = set.size();
        }
    }

    @JCStressTest
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Invariant maintained")
    @State
    public static class RemoveInvariantStress {
        public final ConcurrentCollection<Integer> set;

        public RemoveInvariantStress() {
            this.set = new ConcurrentOrderedSet<>();
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

    @JCStressTest
    @Outcome(id = "true", expect = ACCEPTABLE, desc = "List is sorted")
    @Outcome(id = "false", expect = Expect.FORBIDDEN, desc = "List is unsorted")
    @State
    public static class ConcurrentOrderedListStress {

        private ConcurrentOrderedSet<Integer> list = new ConcurrentOrderedSet<>();

        @Actor
        public void actor1() {
            list.add(1);
            list.add(3);
            list.remove(5);

        }

        @Actor
        public void actor2() {
            list.add(2);
            list.remove(1);
        }

        @Actor
        public void actor3() {
            list.remove(2);
            list.add(5);
        }

        @Arbiter
        public void arbiter(Z_Result r) {
            List<Integer> ls = list.toList();

            r.r1 = IntStream.range(0, ls.size() - 1)
                    .allMatch(i -> ls.get(i) <= ls.get(i + 1));
        }
    }


    @JCStressTest
    @Outcome(id = "true", expect = ACCEPTABLE, desc = "List is sorted")
    @Outcome(id = "false", expect = Expect.FORBIDDEN, desc = "List is unsorted")
    @State
    public static class ConcurrentOrderedRandomListStress {

        private ConcurrentOrderedSet<Integer> set = new ConcurrentOrderedSet<>();

        @Actor
        public void actor1() {
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
                    set.add(ThreadLocalRandom.current().nextInt(30));
                }else set.remove(ThreadLocalRandom.current().nextInt(20));
            }
        }

        @Arbiter
        public void arbiter(Z_Result r) {
            List<Integer> ls = set.toList();

            r.r1 = IntStream.range(0, ls.size() - 1)
                    .allMatch(i -> ls.get(i) <= ls.get(i + 1));
        }
    }



}
