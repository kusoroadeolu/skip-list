package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.ConcurrentCollection;
import io.github.kusoroadeolu.sl.ConcurrentEFUnrolledSet;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
@State(Scope.Benchmark)
@Threads(8)
public class LocalEFIsolationBench {
    @Param({"256"})
    int keySpaceSize;

    @Param({"LOCAL_EF"})
    private String type;

    private ConcurrentEFUnrolledSet<Integer> set;
    private ZipfianGenerator zipf;

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.OPERATIONS)
    public static class ThreadState {
        SplittableRandom rng;
        public int nodeExists;
        public int nodeDoesntExist;

        @Setup(Level.Trial)
        public void setup() {
            rng = new SplittableRandom();
        }

        @TearDown(Level.Iteration)
        public void teardown(LocalEFIsolationBench benchmark) {
            nodeExists  = benchmark.set.nodeExistsCount();
            nodeDoesntExist  = benchmark.set.nodeDoesntExistCount();
            benchmark.set.reset();
        }
    }

    @TearDown
    public void teardown() {
        set.clear();
    }

    @Setup(Level.Trial)
    public void setup() {
        set = switch (type) {
            case "LOCAL_EF" -> new ConcurrentEFUnrolledSet<>();
            default -> throw new IllegalArgumentException();
        };
        zipf      = new ZipfianGenerator(keySpaceSize, 2.0);
    }


//    @Benchmark
//    public void eightyWriteTwentyRead(ThreadState ts, Blackhole bh) {
//        op(set, ts, bh);
//    }

    @Benchmark
    public void fullWrite(ThreadState ts, Blackhole bh) {
        fullWrite(set, ts, bh);
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
                    .include(LocalEFIsolationBench.class.getSimpleName())
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }
}

/*
* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                             256  LOCAL_EF  thrpt   10  4.815 ± 0.244  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist             256  LOCAL_EF  thrpt   10    ≈ 0          ops/us
LocalEFIsolationBench.fullWrite:nodeExists                  256  LOCAL_EF  thrpt   10  1.832 ± 0.079  ops/us

* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                             256  LOCAL_EF  thrpt   10  0.793 ± 0.135  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist             256  LOCAL_EF  thrpt   10  0.076 ± 0.013  ops/us
LocalEFIsolationBench.fullWrite:nodeExists                  256  LOCAL_EF  thrpt   10  0.886 ± 0.049  ops/us
* */



/*
* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                           10000  LOCAL_EF  thrpt   10  1.489 ± 0.206  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist           10000  LOCAL_EF  thrpt   10  0.143 ± 0.020  ops/us
LocalEFIsolationBench.fullWrite:nodeExists                10000  LOCAL_EF  thrpt   10  0.631 ± 0.035  ops/us
*
* Benchmark                                        (keySpaceSize)    (type)   Mode  Cnt  Score   Error   Units
LocalEFIsolationBench.fullWrite                           10000  LOCAL_EF  thrpt   10  4.337 ± 0.229  ops/us
LocalEFIsolationBench.fullWrite:nodeDoesntExist           10000  LOCAL_EF  thrpt   10    ≈ 0          ops/us
LocalEFIsolationBench.fullWrite:nodeExists                10000  LOCAL_EF  thrpt   10  1.657 ± 0.112  ops/us
* */