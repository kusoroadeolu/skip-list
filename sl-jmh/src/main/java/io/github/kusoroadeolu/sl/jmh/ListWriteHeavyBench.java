package io.github.kusoroadeolu.sl.jmh;

import io.github.kusoroadeolu.sl.*;
import io.github.kusoroadeolu.sl.jmh.SkipListBench.JDKConcurrentSkipList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


/*

╭ io.github.kusoroadeolu.sl.jmh.ListWriteHeavyBench.eightThreads ─╮
│  Type          Score        Error        Unit                   │
│  ------------- ------------ ------------ -----                  │
│  LF_FR         76378.274    ± 1426.591   ops/s                  │
│  UNROLLED      7657255.590  ± 128373.169 ops/s                  │
│  JDK           16410942.074 ± 200789.446 ops/s                  │
│  PC_LS         47893.272    ± 1939.970   ops/s                  │
│  ELIM_UNROLLED 5677319.751  ± 125020.128 ops/s                  │
│  LAZY          96179.135    ± 785.251    ops/s                  │
│  LAZY_COARSE   95881.269    ± 2773.335   ops/s                  │
│  LOCK          16548.406    ± 470.088    ops/s                  │
│  LOCAL_EF      5165519.845  ± 174457.820 ops/s                  │
╰─────────────────────────────────────────────────────────────────╯

Generated with JMHPretty
* */

/*
╭─────── io.github.kusoroadeolu.sl.jmh.ListWriteHeavyBench.eightThreads ───────╮
│  Type          Score   Error   P99      P99.9     P99.99    Max       Unit   │
│  ------------- ------- ------- -------- --------- --------- --------- -----  │
│  LF_FR         39.052  ± 0.180 89.728   786.961   2393.095  30441.472 us/op  │
│  PC_LS         243.078 ± 5.586 6146.376 24510.464 48658.239 93192.192 us/op  │
│  ELIM_UNROLLED 1.909   ± 0.040 14.096   53.376    1652.736  11042.816 us/op  │
│  LAZY          37.725  ± 0.119 82.560   657.408   2088.960  19595.264 us/op  │
│  LAZY_COARSE   37.267  ± 0.096 82.176   713.728   1787.904  10174.464 us/op  │
│  LOCK          241.653 ± 2.479 3469.312 4325.376  5201.920  6520.832  us/op  │
│  UNROLLED      1.598   ± 0.036 2.400    28.992    1132.544  14385.152 us/op  │
│  EF_UNROLLED   119.099 ± 0.498 310.784  1594.077  9907.991  38666.240 us/op  │
│  LOCAL_EF      2.124   ± 0.063 3.300    31.072    908.288   31948.800 us/op  │
╰──────────────────────────────────────────────────────────────────────────────╯
Generated with JMHPretty

* */

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx8g", "-Xms8g"})
public class ListWriteHeavyBench { //50% adds, 40% removes, 10% contains
    private ConcurrentCollection<Integer> set;
    @Param({"UNROLLED"})
    //@Param({"LF_FR","UNROLLED", "JDK" ,"PC_LS", "ELIM_UNROLLED", "LAZY", "LAZY_COARSE", "LOCK", "LOCAL_EF"})
    private String type;

    @Setup
    public void setup() {
        set = switch (type) {
            case "LF_FR" -> new ConcurrentOrderedList<>();
            case "ELIM_UNROLLED" -> new EliminationUnrolledConcurrentList<>();
            case "LAZY" -> new LazySyncList<>();
            case "LAZY_COARSE" -> new LazyCoarseSyncList<>();
            case "LOCK" -> new LockedOrderedLL<>();
            case "UNROLLED" -> new UnrolledConcurrentList<>();
          //  case "EF_UNROLLED" -> new EFUnrolledConcurrentList<>();
            case "LOCAL_EF" -> new LocalEFUnrolledConcurrentList<>();
            case "PC_LS" -> new PCLinkedList<>();
            case "JDK" -> new JDKConcurrentSkipList<>();

            default -> throw new IllegalArgumentException();
        };

    }


    @Threads(8)
    @Benchmark
    public void eightThreads(Blackhole bh) {
        doWork(bh);
    }

    private void doWork(Blackhole bh) {
        int key = ThreadLocalRandom.current().nextInt(0, 600_000);
        int op = ThreadLocalRandom.current().nextInt(100);
        if (op < 90) {
            bh.consume(set.add(key));
        } else if (op < 99) {
            bh.consume(set.remove(key));
        } else{
            bh.consume(set.contains(key));
        }
    }

    static class BenchRunner {
        static void main() throws RunnerException {
            Options options = new OptionsBuilder()
                    .include(ListWriteHeavyBench.class.getSimpleName())
                    .addProfiler(JavaFlightRecorderProfiler.class, "dir=C:\\jfr-sl")
                    .build();
            new org.openjdk.jmh.runner.Runner(options).run();        }
    }
}

/*
* With cache
* green = fastest/best, red = slowest/worst

╭ io.github.kusoroadeolu.sl.jmh.ListWriteHeavyBench.eightThreads ─╮
│  Type     Score     Error      Unit                             │
│  -------- --------- ---------- -----                            │
│  UNROLLED 28286.922 ± 2380.826 ops/s                            │
╰─────────────────────────────────────────────────────────────────╯
*
╭ io.github.kusoroadeolu.sl.jmh.ListReadHeavyBench.eightThreads ─╮
│  Type     Score     Error      Unit                            │
│  -------- --------- ---------- -----                           │
│  UNROLLED 87781.691 ± 8792.279 ops/s                           │
╰────────────────────────────────────────────────────────────────╯

* */

/*
╭ io.github.kusoroadeolu.sl.jmh.ListReadHeavyBench.eightThreads ─╮
│  Type     Score      Error      Unit                           │
│  -------- ---------- ---------- -----                          │
│  UNROLLED 102013.837 ± 7435.288 ops/s                          │
╰────────────────────────────────────────────────────────────────╯

╭ io.github.kusoroadeolu.sl.jmh.ListWriteHeavyBench.eightThreads ─╮
│  Type     Score     Error      Unit                             │
│  -------- --------- ---------- -----                            │
│  UNROLLED 29008.614 ± 2429.061 ops/s                            │
╰─────────────────────────────────────────────────────────────────╯

* */