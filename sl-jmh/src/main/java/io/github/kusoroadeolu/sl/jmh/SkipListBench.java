package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.FineGrainedSkipList;
import io.github.kusoroadeolu.sl.OptimisticConcurrentSkipListSet;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Set;
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
SkipListBench.twoThreads       JDK  thrpt   30   6.304 ± 0.351  ops/us
SkipListBench.twoThreads       OPT  thrpt   30   2.039 ± 0.259  ops/us
* */

// After the compare to optimization
/*
*Benchmark                    Mode  Cnt  Score   Error   Units
SkipListBench.eightThreads  thrpt   30  5.335 ± 0.272  ops/us
SkipListBench.fourThreads   thrpt   30  4.292 ± 0.203  ops/us
SkipListBench.twoThreads    thrpt   30  3.167 ± 0.212  ops/us
* */

//After the current level optimization
/*
Benchmark                    Mode  Cnt  Score   Error   Units
SkipListBench.eightThreads  thrpt   30  9.133 ± 0.285  ops/us
SkipListBench.fourThreads   thrpt   30  6.972 ± 0.138  ops/us
SkipListBench.twoThreads    thrpt   30  5.397 ± 0.295  ops/us
* */

/*
* Benchmark                     (type)   Mode  Cnt   Score   Error   Units
SkipListBench.eightThreads       JDK  thrpt   30  12.116 ± 0.416  ops/us
SkipListBench.eightThreads       OPT  thrpt   30   9.141 ± 0.330  ops/us
SkipListBench.eightThreads        FG  thrpt   30   4.384 ± 0.076  ops/us
SkipListBench.fourThreads        JDK  thrpt   30   9.242 ± 0.279  ops/us
SkipListBench.fourThreads        OPT  thrpt   30   7.012 ± 0.313  ops/us
SkipListBench.fourThreads         FG  thrpt   30   3.821 ± 0.058  ops/us
SkipListBench.sixteenThreads     JDK  thrpt   30  13.036 ± 0.168  ops/us
SkipListBench.sixteenThreads     OPT  thrpt   30   9.676 ± 0.208  ops/us
SkipListBench.sixteenThreads      FG  thrpt   30   4.584 ± 0.067  ops/us
SkipListBench.twoThreads         JDK  thrpt   30   6.534 ± 0.391  ops/us
SkipListBench.twoThreads         OPT  thrpt   30   5.156 ± 0.367  ops/us
SkipListBench.twoThreads          FG  thrpt   30   3.216 ± 0.138  ops/us
* */
public class SkipListBench {
    private Set<Integer> set;
    // @Param({"JDK", "OPT", "FG"}) //JDK, Ours, fine grained(from a random repository that implemented the same paper)
    private String type;

    @Setup
    public void setup() {
//        set = switch (type) {
//            case "JDK" -> new ConcurrentSkipListSet<>();
//            case "OPT" -> new OptimisticConcurrentSkipListSet<>(62);
//            case "FG" -> new FineGrainedSkipList(62);
//            default -> throw new IllegalArgumentException();
//        };

        set = new OptimisticConcurrentSkipListSet<>(124);
    }

    @Threads(2)
    @Benchmark
    public void twoThreads(Blackhole bh) {
        doWork(bh);
    }

    @Threads(4)
    @Benchmark
    public void fourThreads(Blackhole bh) {
        doWork(bh);
    }

    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        doWork(bh);
    }


    @Threads(16)
    @Benchmark
    public void sixteenThreads(Blackhole bh) {
        doWork(bh);
    }
//
//    @Threads(32)
//    @Benchmark
//    public void thirtyTwoThreads(Blackhole bh, ThreadState ts) {
//        doWork(bh, ts);
//    }

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
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }
}
