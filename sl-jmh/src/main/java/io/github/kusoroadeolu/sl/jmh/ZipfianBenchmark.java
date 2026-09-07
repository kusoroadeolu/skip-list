package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.ConcurrentCollection;
import io.github.kusoroadeolu.sl.EliminationUnrolledConcurrentList;
import io.github.kusoroadeolu.sl.LocalEFUnrolledConcurrentList;
import io.github.kusoroadeolu.sl.UnrolledConcurrentList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.HashSet;
import java.util.Set;
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

/*
*
* Flow - try acquire lock - otherwise spin on different indices in the arena for a node, waiting for a thread which holds
* the lock to combine with us
*
* When we hold the lock, we always create a new hashset (which uses a hashmap underneath), scan the arena for waiting threads
* and tell threads which don't belong to that specific node to retry (which in retrospect now doesnt make sense, since threads
* backoff and we still hold the lock to the node they need to create a new node to)
* */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
//@Fork(value = 2, jvmArgs = "-XX:TieredStopAtLevel=1")
@State(Scope.Benchmark)
@Threads(8)
@Fork(value = 2, jvmArgs = {"-Xmx8g", "-Xms8g"})
public class ZipfianBenchmark {

    @Param({"100000"})
    int keySpaceSize;

    @Param({"UNROLLED"})
    private String type;

    private ConcurrentCollection<Integer> set;
    private ZipfianGenerator zipf;

   @State(Scope.Thread)
 //  @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class ThreadState {
        SplittableRandom rng;
//        public int nodeSuccesses;
//        public int arenaSuccesses;

        @Setup(Level.Trial)
        public void setup() {
            rng = new SplittableRandom();
        }

//        @TearDown(Level.Iteration)
//        public void teardown(ZipfianBenchmark benchmark) {
//            EliminationMetrics m = benchmark.set.metrics();
//            nodeSuccesses  = m.nodeSuccesses();
//            arenaSuccesses = m.arenaSuccesses();
//            m.reset();
//        }
    }


    @Setup(Level.Trial)
    public void setup() {
        set = switch (type) {
            case "ELIM_UNROLLED" -> new EliminationUnrolledConcurrentList<>();
            case "UNROLLED" -> new UnrolledConcurrentList<>();
            case "LOCAL_EF" -> new LocalEFUnrolledConcurrentList<>();
            default -> throw new IllegalArgumentException();
        };
        zipf      = new ZipfianGenerator(keySpaceSize, 2.0);
        int prefill = (int) (0.1 * keySpaceSize);

        Set<Integer> added = new HashSet<>();
        for (int i = 0; i < prefill;) {
            int val = ThreadLocalRandom.current().nextInt(0, keySpaceSize);
            if (!added.add(val)) continue;
            set.add(val);
            ++i;
        }
    }


    @Benchmark
    public void eightyWriteTwentyRead(ThreadState ts, Blackhole bh) {
        op(set, ts, bh);
    }

    @Benchmark
    public void fullWrite(ThreadState ts, Blackhole bh) {
        fullWrite(set, ts, bh);
    }
//
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
╭ io.github.kusoroadeolu.sl.jmh.ZipfianBenchmark.eightyWriteTwentyRead ─╮
│  KeySpaceSize Type          Score Error   Unit                        │
│  ------------ ------------- ----- ------- ------                      │
│  64           ELIM_UNROLLED 6.816 ± 0.853 ops/us                      │
│  64           UNROLLED      4.655 ± 0.198 ops/us                      │
│  64           LOCAL_EF      5.742 ± 0.186 ops/us                      │
│  128          ELIM_UNROLLED 7.096 ± 0.504 ops/us                      │
│  128          UNROLLED      4.900 ± 0.074 ops/us                      │
│  128          LOCAL_EF      5.743 ± 0.138 ops/us                      │
│  256          ELIM_UNROLLED 7.033 ± 0.468 ops/us                      │
│  256          UNROLLED      4.836 ± 0.120 ops/us                      │
│  256          LOCAL_EF      5.419 ± 0.162 ops/us                      │
╰───────────────────────────────────────────────────────────────────────╯


╭ io.github.kusoroadeolu.sl.jmh.ZipfianBenchmark.fullWrite ─╮
│  KeySpaceSize Type          Score Error   Unit            │
│  ------------ ------------- ----- ------- ------          │
│  64           ELIM_UNROLLED 6.016 ± 0.532 ops/us          │
│  64           UNROLLED      4.858 ± 0.437 ops/us          │
│  64           LOCAL_EF      4.788 ± 0.171 ops/us          │
│  128          ELIM_UNROLLED 5.637 ± 0.479 ops/us          │
│  128          UNROLLED      4.748 ± 0.330 ops/us          │
│  128          LOCAL_EF      3.530 ± 1.284 ops/us          │
│  256          ELIM_UNROLLED 5.832 ± 0.388 ops/us          │
│  256          UNROLLED      4.584 ± 0.429 ops/us          │
│  256          LOCAL_EF      3.840 ± 0.813 ops/us          │
╰───────────────────────────────────────────────────────────╯
Generated with JMHPretty



╭─────── io.github.kusoroadeolu.sl.jmh.ZipfianBenchmark.eightyWriteTwentyRead ───────╮
│  KeySpaceSize Type          Score Error   P99    P99.9   P99.99   Max       Unit   │
│  ------------ ------------- ----- ------- ------ ------- -------- --------- -----  │
│  64           ELIM_UNROLLED 1.770 ± 0.052 12.992 67.584  1790.217 37748.736 us/op  │
│  64           UNROLLED      1.918 ± 0.014 64.640 93.056  153.088  3051.520  us/op  │
│  64           LOCAL_EF      2.827 ± 0.046 32.768 283.301 1595.663 6012.928  us/op  │
│  128          ELIM_UNROLLED 1.812 ± 0.048 15.600 70.400  1490.944 22380.544 us/op  │
│  128          UNROLLED      2.019 ± 0.018 68.224 125.312 191.149  4177.920  us/op  │
│  128          LOCAL_EF      2.196 ± 0.040 23.200 81.664  1603.584 20152.320 us/op  │
│  256          ELIM_UNROLLED 1.309 ± 0.049 11.792 33.664  271.782  31981.568 us/op  │
│  256          UNROLLED      1.964 ± 0.014 63.872 97.792  157.696  2510.848  us/op  │
│  256          LOCAL_EF      6.684 ± 0.265 31.296 626.688 9846.784 33062.912 us/op  │
╰────────────────────────────────────────────────────────────────────────────────────╯


╭────────────── io.github.kusoroadeolu.sl.jmh.ZipfianBenchmark.fullWrite ───────────────╮
│  KeySpaceSize Type          Score  Error   P99    P99.9    P99.99    Max       Unit   │
│  ------------ ------------- ------ ------- ------ -------- --------- --------- -----  │
│  64           ELIM_UNROLLED 1.502  ± 0.052 12.896 35.776   416.870   26705.920 us/op  │
│  64           UNROLLED      2.068  ± 0.013 63.680 82.304   114.432   1488.896  us/op  │
│  64           LOCAL_EF      7.146  ± 0.243 35.264 1015.808 9420.800  28049.408 us/op  │
│  128          ELIM_UNROLLED 1.580  ± 0.061 12.992 36.160   523.325   24576.000 us/op  │
│  128          UNROLLED      2.232  ± 0.026 64.064 88.704   424.448   14860.288 us/op  │
│  128          LOCAL_EF      13.577 ± 0.349 54.464 3778.343 12304.384 31883.264 us/op  │
│  256          ELIM_UNROLLED 1.709  ± 0.070 14.192 38.656   552.808   26050.560 us/op  │
│  256          UNROLLED      2.154  ± 0.016 64.960 117.248  187.904   5005.312  us/op  │
│  256          LOCAL_EF      11.083 ± 0.336 42.368 3235.840 11812.864 31784.960 us/op  │
╰───────────────────────────────────────────────────────────────────────────────────────╯
Generated with JMHPretty

* */

/*
* Bad run 1
* {
* 0=[0],
* 1=[79, 38, 76, 68, 3, 48, 69, 22, 81, 47, 44, 65, 45, 29, 50, 56, 8, 11, 13, 62, 59, 85, 15, 1, 54, 18, 72, 25, 53, 70, 80, 33, 71, 67, 64, 49, 60, 74, 77, 24],
* 89=[127, 146, 97, 142, 105, 139, 123, 103, 116, 111, 91, 148, 126, 95, 144, 128, 90, 130, 143, 113, 141, 133, 93, 101, 132, 89, 134, 136, 99, 106, 138, 112, 94, 100, 117, 98, 129, 121, 114],
* 151=[187, 190, 184, 208, 169, 180, 204, 167, 178, 183, 159, 166, 172, 206, 175, 152, 162, 188, 157, 181, 197, 191, 193, 154, 182, 202, 195, 176, 209],
* 211=[222, 218, 249, 246, 240, 225, 238, 255, 212, 216, 230, 244, 245, 226, 214, 227, 229, 235]
* }
*
* {
* 1=[26, 9, 71, 85, 45, 53, 17, 70, 76, 4, 8, 65, 12, 32, 6, 7, 58, 60, 46, 13, 81, 1, 24, 21, 37, 80, 86, 30, 51, 27, 22, 47, 20, 40, 29, 73, 68, 82, 59, 50, 44, 54],
* 89=[121, 115, 141, 103, 137, 111, 118, 113, 152, 90, 153, 93, 98, 154, 123, 157, 96, 149, 144, 101, 128, 146, 129, 155, 89, 102, 117, 130, 133],
* 158=[249, 169, 212, 228, 190, 192, 226, 176, 180, 185, 177, 208, 227, 207, 248, 168, 206, 239, 238, 235, 175, 171, 205, 204, 231, 181, 202, 193, 243, 214, 203, 220, 165, 196, 183, 223, 211, 209, 250, 245, 246, 233, 198, 167, 158, 234, 242, 189, 166, 247, 178, 161, 187, 236, 252]
* }
*
*
* Good run 1
*
* {
* 0=[48, 46, 52, 0, 5, 22, 49, 34, 74, 36, 20, 61, 26, 64, 38, 11, 8, 44, 6, 16, 37, 54, 25, 4, 27, 28, 32, 43, 10, 50, 29, 55, 67, 39, 53, 82, 31, 65, 57, 71],
* 83=[84, 140, 142, 122, 86, 125, 143, 119, 85, 114, 88, 156, 107, 158, 153, 87, 160, 124, 128, 129, 92, 159, 93, 120, 118, 94, 135, 147, 104, 95, 100, 103, 148, 155, 109, 136, 91, 99, 151, 133, 127, 145, 115, 97],
* 161=[199, 184, 190, 206, 163, 203, 179, 194, 176, 204, 230, 182, 213, 201, 165, 239, 210, 196, 229, 178, 250, 170, 200, 220, 255, 231, 205, 251, 227, 197, 232, 225, 175, 246, 224, 222, 168, 164, 248, 172, 209, 193, 245, 185, 214, 169]
* }
*
* Good run 2
*
* {
* 0=[71, 3, 15, 5, 0, 70, 35, 33, 20, 10, 16, 18, 12, 42, 30, 7, 48, 45, 39, 72, 63, 65, 67, 66, 9, 13, 28, 8, 11, 40, 74, 68, 43, 23, 61, 77, 44, 27, 46, 73, 21, 41],
* 78=[132, 151, 89, 81, 114, 115, 127, 83, 78, 111, 94, 130, 122, 147, 98, 117, 97, 153, 143, 116, 106, 146, 107, 123, 120, 135, 108, 112, 88, 95, 104, 105, 90, 141, 110, 102, 85, 134],
* 159=[195, 177, 247, 230, 170, 232, 172, 181, 243, 204, 187, 223, 160, 221, 213, 186, 194, 207, 173, 228, 234, 229, 212, 178, 180, 224, 179, 220, 184, 169, 244, 219, 246, 176, 233, 163, 248, 239, 159, 198, 210, 174, 226, 168, 164, 203, 206, 185, 190]
* }
*
* Base unrolled
* {
* 0=[37, 39, 29, 16, 45, 12, 41, 1, 7, 0, 13, 2, 50, 42, 75, 18, 35, 65, 43, 66, 34, 36, 64, 27, 74, 32, 57, 69, 54, 61, 17, 47, 55, 51, 30, 38, 56, 19, 58, 25, 49, 14, 44, 63, 9, 40, 24, 8],
* 76=[107, 132, 102, 124, 110, 89, 98, 122, 148, 86, 125, 91, 95, 104, 76, 139, 80, 83, 147, 133, 100, 155, 82, 94, 136, 93, 88, 130, 115, 151, 113, 150, 128, 109, 79, 84, 117, 131, 146, 143, 101],
* 156=[200, 184, 193, 212, 194, 195, 248, 245, 209, 242, 198, 173, 255, 166, 188, 216, 172, 185, 219, 201, 183, 170, 156, 222, 158, 175, 246, 162, 250, 211, 180, 221, 226, 240, 238, 176, 207, 181, 231, 230, 196, 239, 210, 213, 252, 237, 177, 233, 161, 199, 204, 160]
* }
* */