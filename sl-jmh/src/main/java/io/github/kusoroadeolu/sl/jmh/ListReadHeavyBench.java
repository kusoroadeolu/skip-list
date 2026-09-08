package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/*
╭ io.github.kusoroadeolu.sl.jmh.ListReadHeavyBench.eightThreads ─╮
│  Type        Score Error   Unit                                │
│  ----------- ----- ------- ------                              │
│  LF_FR       0.092 ± 0.003 ops/us                              │
│  PC_LS       0.323 ± 0.021 ops/us                              │
│  LAZY        0.110 ± 0.002 ops/us                              │
│  LAZY_COARSE 0.109 ± 0.003 ops/us                              │
│  LOCK        0.020 ± 0.000 ops/us                              │
│  UNROLLED    7.299 ± 0.125 ops/us                              │
╰────────────────────────────────────────────────────────────────╯
Generated with JMHPretty


╭───── io.github.kusoroadeolu.sl.jmh.ListReadHeavyBench.eightThreads ──────╮
│  Type        Score   Error   P99      P99.9    P99.99   Max       Unit   │
│  ----------- ------- ------- -------- -------- -------- --------- -----  │
│  LF_FR       73.008  ± 0.226 204.800  303.104  3371.882 33095.680 us/op  │
│  PC_LS       23.463  ± 0.131 105.984  771.069  2990.080 32374.784 us/op  │
│  LAZY        60.250  ± 0.187 142.848  220.076  2764.800 26181.632 us/op  │
│  LAZY_COARSE 61.044  ± 0.264 144.384  266.240  4653.056 36175.872 us/op  │
│  LOCK        177.835 ± 1.634 2560.000 6033.719 8437.760 13221.888 us/op  │
│  UNROLLED    1.289   ± 0.045 2.100    13.696   178.414  34078.720 us/op  │
╰──────────────────────────────────────────────────────────────────────────╯
Generated with JMHPretty
* */

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx8g", "-Xms8g"})
public class ListReadHeavyBench {
    private ConcurrentCollection<Integer> set;

    @Param({"LockFreeSet", "UnrolledSet" ,"PathCopyingSet", "EliminationUnrolledSet", "LazyOptimisticSet", "LazyCoarseOptimisticSet", "LockedSet", "EliminationCombiningUnrolledSet"})
    private String type;

    static final int PREFILL = 5_00_000;
    static final int RANGE = 6_000_000;

    @Setup
    public void setup() {
        set = switch (type) {
            case "LockFreeSet" -> new ConcurrentOrderedSet<>();
            case "UnrolledSet" -> new ConcurrentUnrolledSet<>();
            case "PathCopyingSet" -> new PathCopyingSet<>();
            case "EliminationUnrolledSet" -> new ConcurrentEliminationUnrolledSet<>();
            case "LazyOptimisticSet" -> new LazyOptimisticSet<>();
            case "LazyCoarseOptimisticSet" -> new LazyOptimisticCoarseSet<>();
            case "LockedSet" -> new LockedOrderedSet<>();
            case "EliminationCombiningUnrolledSet" -> new ConcurrentCombiningUnrolledSet<>();
            default -> throw new IllegalArgumentException();
        };

        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < PREFILL;) {
            int value = ThreadLocalRandom.current().nextInt(0, RANGE);
            boolean added = set.add(value);
            if(added) set.add(value);
            ++i;
        }
    }



    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        doWork(bh);
    }



    private void doWork(Blackhole bh) {
        int key = ThreadLocalRandom.current().nextInt(0, RANGE);
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
                    .include(ListReadHeavyBench.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }

}
