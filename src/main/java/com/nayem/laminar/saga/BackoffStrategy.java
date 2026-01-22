package com.nayem.laminar.saga;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for calculating retry backoff with jitter.
 */
public class BackoffStrategy {

    private final double jitterPercent;

    public BackoffStrategy(double jitterPercent) {
        this.jitterPercent = Math.max(0.0, Math.min(1.0, jitterPercent));
    }

    /**
     * Calculates the backoff delay with exponential backoff and jitter.
     *
     * @param attempt     The retry attempt number (0-indexed)
     * @param baseDelayMs The base delay in milliseconds
     * @param maxDelayMs  Maximum delay in milliseconds
     * @return Delay in milliseconds
     */
    public long calculateBackoff(int attempt, long baseDelayMs, long maxDelayMs) {
        long exponentialDelay = baseDelayMs * (1L << attempt);
        long cappedDelay = Math.min(exponentialDelay, maxDelayMs);

        if (jitterPercent == 0.0) {
            return cappedDelay;
        }

        long jitterRange = (long) (cappedDelay * jitterPercent);
        long fixedPortion = cappedDelay - jitterRange;
        long randomPortion = ThreadLocalRandom.current().nextLong(jitterRange + 1);
        
        return fixedPortion + randomPortion;
    }
}