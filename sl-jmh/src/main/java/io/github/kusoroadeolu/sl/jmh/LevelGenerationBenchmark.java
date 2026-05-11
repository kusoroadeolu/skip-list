package io.github.kusoroadeolu.sl.jmh;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class LevelGenerationBenchmark {

    private static final double PROBABILITY = 0.5;
    private static final int HEIGHT = 32;




    @Benchmark
    public void log(Blackhole bh){
        bh.consume(logBased());
    }

    @Benchmark
    public void bit(Blackhole bh){
        bh.consume(bitManipulation());
    }

    public int logBased() {
        double r = Math.max(ThreadLocalRandom.current().nextDouble(), Double.MIN_VALUE);
        int level = (int)(Math.log(r) / Math.log(PROBABILITY)) + 1;
        return Math.min(level, HEIGHT - 1);
    }

    public int bitManipulation() {
        int level = 1;
        int random = ThreadLocalRandom.current().nextInt();
        while ((random & 1) == 1 && level < HEIGHT - 1) {
            level++;
            random >>= 1;
        }
        return level;
    }


}