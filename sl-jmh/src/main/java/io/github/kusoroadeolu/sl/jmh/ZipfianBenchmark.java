package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;



/* 100% Writes
Benchmark                                                 (keySpaceSize)         (type)   Mode  Cnt  Score   Error   Units
ElimUnrolledZipfianBenchmark.fullWrite                             64  ELIM_UNROLLED  thrpt   30  3.131 ± 0.096  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:arenaSuccesses              64  ELIM_UNROLLED  thrpt   30  1.217 ± 0.037  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:nodeSuccesses               64  ELIM_UNROLLED  thrpt   30  1.307 ± 0.045  ops/us
ElimUnrolledZipfianBenchmark.fullWrite                            128  ELIM_UNROLLED  thrpt   30  2.132 ± 0.426  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:arenaSuccesses             128  ELIM_UNROLLED  thrpt   30  0.806 ± 0.183  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:nodeSuccesses              128  ELIM_UNROLLED  thrpt   30  0.931 ± 0.157  ops/us
ElimUnrolledZipfianBenchmark.fullWrite                            256  ELIM_UNROLLED  thrpt   30  2.363 ± 0.475  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:arenaSuccesses             256  ELIM_UNROLLED  thrpt   30  0.911 ± 0.191  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:nodeSuccesses              256  ELIM_UNROLLED  thrpt   30  0.999 ± 0.189  ops/us
*/

/*
* so i did some profiling(for the suspicious results) and its pretty surprising.
* My guess that  maybe it was benchmarking issue or JIT not warming up fully, but after looking at the profile data, I realized that the contention was situated solely in the add method(especially in the elimination arena).
* Now while this doesnt mean much, I dug deeper and the main path that was flagged by the profiler was the inner spin loop while a thread is waiting to be eliminated.
* That only meant one thing, threads were waiting the full sprint in the elimination arena, which also meant two things either
* 1. Removes were never reaching the elim arena or
* 2. Removes were just unlucky and the values of removes were never equal to that of adds in the elim arena.
* I then looked at the remove side, surely if removes were reaching the elim arena we'll see some cpu samples there, but they weren't.
* Upwards the main contention path for removes was checking if a value existed in a node. So all in all, for the structure to get such low thrpt,
*  removes are highly dependent on value, meaning if it doesnt exist in the node,
* they never make it to the elim arena, subsequently, for adds, if it exists in the node, it'd never make it to the elim arena which is counterintuitive haha.
*
* 2 simple ways to reduce this were:
* 1. Remove the set invariant
* 2. Force remove ops to always scan that node's elim array if the value wasn't present in the list
*
* While this didnt fully get rid of the issue(as the high err margins in some results) it increased the number of successful eliminations in the arena to an almost 1:1 ratio with the node successes
* and reduced the amount of times this happened throughout the benchmark
* */


/* After
Benchmark                                  (keySpaceSize)         (type)   Mode  Cnt  Score   Error   Units
ElimUnrolledZipfianBenchmark.fullWrite                             64  ELIM_UNROLLED  thrpt   30  4.381 ± 0.143  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:arenaSuccesses              64  ELIM_UNROLLED  thrpt   30  1.461 ± 0.063  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:nodeSuccesses               64  ELIM_UNROLLED  thrpt   30  1.997 ± 0.063  ops/us
ElimUnrolledZipfianBenchmark.fullWrite                            128  ELIM_UNROLLED  thrpt   30  4.229 ± 0.124  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:arenaSuccesses             128  ELIM_UNROLLED  thrpt   30  1.454 ± 0.044  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:nodeSuccesses              128  ELIM_UNROLLED  thrpt   30  1.893 ± 0.058  ops/us
ElimUnrolledZipfianBenchmark.fullWrite                            256  ELIM_UNROLLED  thrpt   30  3.938 ± 0.361  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:arenaSuccesses             256  ELIM_UNROLLED  thrpt   30  1.172 ± 0.276  ops/us
ElimUnrolledZipfianBenchmark.fullWrite:nodeSuccesses              256  ELIM_UNROLLED  thrpt   30  1.950 ± 0.057  ops/us
* */

/*
* Benchmark                   (keySpaceSize)         (type)   Mode  Cnt  Score   Error   Units
ElimUnrolledZipfianBenchmark.fullWrite              64  UNROLLED  thrpt   30  2.055 ± 0.177  ops/us
ElimUnrolledZipfianBenchmark.fullWrite             128  UNROLLED  thrpt   30  1.726 ± 0.056  ops/us
ElimUnrolledZipfianBenchmark.fullWrite             256  UNROLLED  thrpt   30  1.863 ± 0.163  ops/us
* */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
//@Fork(value = 2, jvmArgs = "-XX:TieredStopAtLevel=1")
@State(Scope.Benchmark)
@Threads(8)
@Fork(value = 3, jvmArgs = {"-Xmx8g", "-Xms8g"})
public class ZipfianBenchmark {

    @Param({"500000"})
    int keySpaceSize;

    @Param({"LockFreeSet", "UnrolledSet" ,"PathCopyingSet", "EliminationUnrolledSet", "LazyOptimisticSet", "LazyCoarseOptimisticSet", "LockedSet", "EliminationCombiningUnrolledSet"})
    private String type;

    private ConcurrentCollection<Integer> set;
    private ZipfianGenerator zipf;
    private static final double PREFILL_RATIO = 0.1;

   @State(Scope.Thread)
    public static class ThreadState {
        SplittableRandom rng;

        @Setup(Level.Trial)
        public void setup() {
            rng = new SplittableRandom();
        }

    }


    @Setup(Level.Trial)
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

        zipf      = new ZipfianGenerator(keySpaceSize, .99);
        int[] keys = new int[keySpaceSize];
        for (int i = 0; i < keySpaceSize; i++) keys[i] = i;
        int prefill = (int) (PREFILL_RATIO * keySpaceSize);

        // Fisher-Yates shuffle, only need to shuffle enough to fill `prefill` slots
        var rng = ThreadLocalRandom.current();
        for (int i = 0; i < prefill; i++) {
            int j = i + rng.nextInt(keySpaceSize - i);
            int tmp = keys[i]; keys[i] = keys[j]; keys[j] = tmp;
            set.add(keys[i]);
        }
    }


    @Benchmark
    public void eightyWriteTwentyRead(ThreadState ts, Blackhole bh) {
        op(set, ts, bh);
    }


    @Benchmark
    public void tenWriteNinetyRead(ThreadState ts, Blackhole bh) {
       read(set, ts, bh);
    }




    private void op(ConcurrentCollection<Integer> set, ThreadState ts, Blackhole bh) {
        int key = zipf.nextInt(ts.rng);
        if (ts.rng.nextDouble() < 0.80) {
            if (ts.rng.nextBoolean()) bh.consume(set.add(key));
            else bh.consume(set.remove(key));
        } else {
            bh.consume(set.contains(key));
        }
    }

    private void read(ConcurrentCollection<Integer> set, ThreadState ts, Blackhole bh) {
        int key = zipf.nextInt(ts.rng);
        if (ts.rng.nextDouble() < 0.10) {
            if (ts.rng.nextBoolean()) bh.consume(set.add(key));
            else bh.consume(set.remove(key));
        } else {
            bh.consume(set.contains(key));
        }
    }

    private void fullWrite(ConcurrentCollection<Integer> set, ThreadState ts, Blackhole bh) {
        int key = zipf.nextInt(ts.rng);
        if (ts.rng.nextBoolean()) bh.consume(set.add(key));
        else bh.consume(set.remove(key));
    }

    static final class ZipfianGenerator {
        private final int      n;
        private final double[] cdf;

        ZipfianGenerator(int n, double exponent) {
            this.n   = n;
            this.cdf = new double[n];
            double sum = 0;
            for (int i = 1; i <= n; i++) sum += 1.0 / Math.pow(i, exponent);
            double running = 0;
            for (int i = 0; i < n; i++) {
                running += (1.0 / Math.pow(i + 1, exponent)) / sum;
                cdf[i]   = running;
            }
        }

        int nextInt(SplittableRandom rng) {
            double u  = rng.nextDouble();
            int lo = 0, hi = n - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cdf[mid] < u) lo = mid + 1;
                else              hi = mid;
            }
            return lo;
        }
    }


    static class BenchRunner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(ZipfianBenchmark.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl-1")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }
}

/*
╭─── io.github.kusoroadeolu.sl.jmh.ZipfianBenchmark.eightyWriteTwentyRead ────╮
│  KeySpaceSize Type                            Score      Error       Unit   │
│  ------------ ------------------------------- ---------- ----------- -----  │
│  500000       LockFreeSet                     34068.440  ± 4369.103  ops/s  │
│  500000       UnrolledSet                     549576.051 ± 26394.525 ops/s  │
│  500000       PathCopyingSet                  9374.403   ± 10591.912 ops/s  │
│  500000       EliminationUnrolledSet          524242.316 ± 27137.899 ops/s  │
│  500000       LazyOptimisticSet               28836.718  ± 4146.438  ops/s  │
│  500000       LazyCoarseOptimisticSet         28646.652  ± 4831.145  ops/s  │
│  500000       LockedSet                       12356.971  ± 831.821   ops/s  │
│  500000       EliminationCombiningUnrolledSet 790340.528 ± 72509.061 ops/s  │
╰─────────────────────────────────────────────────────────────────────────────╯

╭───── io.github.kusoroadeolu.sl.jmh.ZipfianBenchmark.tenWriteNinetyRead ──────╮
│  KeySpaceSize Type                            Score      Error        Unit   │
│  ------------ ------------------------------- ---------- ------------ -----  │
│  500000       LockFreeSet                     74551.459  ± 6677.074   ops/s  │
│  500000       UnrolledSet                     1049847.911 ± 84527.109 ops/s  │
│  500000       PathCopyingSet                  54073.445  ± 6048.876   ops/s  │
│  500000       EliminationUnrolledSet          941411.407  ± 61612.742 ops/s  │
│  500000       LazyOptimisticSet               67275.228  ± 4930.614   ops/s  │
│  500000       LazyCoarseOptimisticSet         67483.754  ± 5849.623   ops/s  │
│  500000       LockedSet                       18097.330  ± 654.326    ops/s  │
│  500000       EliminationCombiningUnrolledSet 750812.484  ± 48019.070 ops/s  │
╰──────────────────────────────────────────────────────────────────────────────╯
* */