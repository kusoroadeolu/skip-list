package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.ConcurrentCollection;
import io.github.kusoroadeolu.sl.FineGrainedSkipList;
import io.github.kusoroadeolu.sl.OptimisticSkipList;
import io.github.kusoroadeolu.sl.SkipListSet;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
//FINE GRAINED lock bench is excluded from here, only mine and the jdk is included
/*
* Benchmark                   (type)   Mode  Cnt   Score   Error   Units
SkipListBench.eightThreads     JDK  thrpt   30  11.884 ± 0.257  ops/us
SkipListBench.eightThreads     OPT  thrpt   30   3.149 ± 0.113  ops/us
SkipListBench.fourThreads      JDK  thrpt   30   9.010 ± 0.169  ops/us
SkipListBench.fourThreads      OPT  thrpt   30   2.609 ± 0.313  ops/us
* */

// After the compare to optimization
/*
*Benchmark                    Mode  Cnt  Score   Error   Units
SkipListBench.eightThreads  thrpt   30  5.335 ± 0.272  ops/us
SkipListBench.fourThreads   thrpt   30  4.292 ± 0.203  ops/us
* */

//After the current level optimization
/*
Benchmark                    Mode  Cnt  Score   Error   Units
SkipListBench.eightThreads  thrpt   30  9.133 ± 0.285  ops/us
SkipListBench.fourThreads   thrpt   30  6.972 ± 0.138  ops/us
* */

/*
* Benchmark                     (type)   Mode  Cnt   Score   Error   Units
SkipListBench.eightThreads     JDK  thrpt   30  12.936 ±  0.254  ops/us
SkipListBench.eightThreads     OPT  thrpt   30  12.310 ±  0.285  ops/us
SkipListBench.eightThreads      FG  thrpt   30   4.420 ±  0.094  ops/us
SkipListBench.fourThreads      JDK  thrpt   30   9.477 ±  0.147  ops/us
SkipListBench.fourThreads      OPT  thrpt   30   9.362 ±  0.592  ops/us
SkipListBench.fourThreads       FG  thrpt   30   3.902 ±  0.103  ops/us
* */

/*
* "
SkipListBench.eightThreads     JDK  avgt   30    0.544 ± 0.015  us/op
SkipListBench.eightThreads     OPT  avgt   30    0.597 ± 0.025  us/op
SkipListBench.eightThreads      FG  avgt   30    1.666 ± 0.051  us/op
SkipListBench.fourThreads      JDK  avgt   30    0.421 ± 0.021  us/op
SkipListBench.fourThreads      OPT  avgt   30    0.388 ± 0.014  us/op
SkipListBench.fourThreads       FG  avgt   30    0.982 ± 0.036  us/op
* */
public class SkipListBench {
    private ConcurrentCollection<Integer> set;
    @Param({"OPT", "JDK" ,"FG"}) //JDK, Ours, fine grained(from a random repository that implemented the same paper)
    private String type;

    @Setup
    public void setup() {
        set = switch (type) {
            case "JDK" -> new JDKConcurrentSkipList<>();
            case "OPT" -> new OptimisticSkipList<>(62);
            case "FG" -> new FineGrainedSkipList(62);
            default -> throw new IllegalArgumentException();
        };

    }

    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        doWork(bh);
    }


    private void doWork(Blackhole bh) {
        int key = ThreadLocalRandom.current().nextInt(10_000);
        int op = ThreadLocalRandom.current().nextInt(100);

        if (op < 90) {
            bh.consume(set.contains(key));
        } else if (op < 99) {
            bh.consume(set.add(key));
        } else {
            bh.consume(set.remove(key));
        }
    }

    static class BenchRunner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(SkipListBench.class.getSimpleName())
                  //  .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }

    static class JDKConcurrentSkipList<E extends Comparable<E>> implements ConcurrentCollection<E> {
        private final ConcurrentSkipListSet<E> set;

        public JDKConcurrentSkipList() {
            this.set = new ConcurrentSkipListSet<>();
        }

        @Override
        public boolean add(E e) {
            return set.add(e);
        }

        @Override
        public boolean remove(Object t) {
            return set.remove(t);
        }

        @Override
        public boolean isEmpty() {
            return set.isEmpty();
        }

        @Override
        public boolean contains(Object t) {
            return set.contains(t);
        }

        @Override
        public void clear() {
            set.clear();
        }

        @Override
        public int size() {
            return set.size();
        }

        @Override
        public List<E> toList() {
            return set.stream().toList();
        }
    }
}
