package com.github.stevestorey.claudeanalyser.model;

/**
 * USD cost of a single message (or a sum of messages), broken into the same
 * four buckets Anthropic invoices on. Cache writes are summed across 5m and
 * 1h tiers — the per-tier breakdown lives upstream in {@link MessageUsage};
 * by the time we have a {@code Cost} the dollar figure is already combined.
 */
public record Cost(double input, double output, double cacheWrite, double cacheRead) {

    /** Identity element for sums; safe to use as a fold seed. */
    public static final Cost ZERO = new Cost(0, 0, 0, 0);

    /** Total dollar cost across all four buckets. */
    public double total() {
        return input + output + cacheWrite + cacheRead;
    }

    /** Component-wise sum. Returns a new {@code Cost}; this record is immutable. */
    public Cost plus(Cost other) {
        return new Cost(
                input + other.input,
                output + other.output,
                cacheWrite + other.cacheWrite,
                cacheRead + other.cacheRead);
    }
}
