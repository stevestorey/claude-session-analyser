package com.github.stevestorey.claudeanalyser.model;

import java.time.Instant;

/**
 * Token usage for a single assistant message, extracted from a session JSONL line's
 * {@code message.usage} object.
 *
 * <p>Cache-write tokens are split by TTL because Anthropic prices them differently:
 * 5-minute writes are cheaper than 1-hour writes. Older entries that only have the
 * flat {@code cache_creation_input_tokens} field are recorded under the 5m bucket
 * by {@code SessionParser}.
 *
 * <p>{@code timestamp} comes from the JSONL line's top-level field, not from inside
 * {@code message}, and is what {@code PlanEstimator} uses to bucket into 5h windows.
 */
public record MessageUsage(
        Instant timestamp,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheCreate5mTokens,
        long cacheCreate1hTokens,
        long cacheReadTokens) {

    /** Sum of every token counted on this message — useful for "size of message" reporting. */
    public long totalTokens() {
        return inputTokens + outputTokens + cacheCreate5mTokens + cacheCreate1hTokens + cacheReadTokens;
    }
}
