package io.github.kusoroadeolu.sl;

import java.util.concurrent.ThreadLocalRandom;

public final class Utils {
    private Utils(){}

    private static final double LOG_PROBABILITY = Math.log(0.5);
    public static int generateLevel(int height) {
        double r = Math.max(ThreadLocalRandom.current().nextDouble(), Double.MIN_VALUE);
        int level = (int)(Math.log(r) / LOG_PROBABILITY) + 1;
        return Math.min(level, height - 1);
    }
}
