package com.github.stevestorey.claudeanalyser;

import com.github.stevestorey.claudeanalyser.model.Cost;
import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import com.github.stevestorey.claudeanalyser.model.ModelRates;

/**
 * Anthropic public list pricing (USD per million tokens) for the Claude 4.x families
 * and the cost-from-usage calculator.
 *
 * <p>Lookup is done by family <em>prefix</em> ({@code claude-opus-},
 * {@code claude-sonnet-}, {@code claude-haiku-}) so that minor version bumps
 * (4-7, 4-6, 4-5-20250929 …) all resolve to the same rate card without code
 * changes. If/when Anthropic changes published prices, edit the three
 * {@code ModelRates} constants below.
 *
 * <p>Anything that isn't a recognised Anthropic model — including local models like
 * {@code gemma-*.gguf} that show up in the data — resolves to {@link ModelRates#ZERO},
 * meaning tokens are still counted upstream but cost is $0. {@link #isKnown} lets
 * callers detect this case for reporting.
 */
public final class Pricing {

    // Per-MTok USD. Order: input, output, cache-write-5m, cache-write-1h, cache-read.
    private static final ModelRates OPUS_4   = new ModelRates(5.00, 25.00, 6.25, 10.00, 0.50);
    private static final ModelRates SONNET_4 = new ModelRates(3.00, 15.00, 3.75,  6.00, 0.30);
    private static final ModelRates HAIKU_4  = new ModelRates(1.00,  5.00, 1.25,  2.00, 0.10);

    private Pricing() {}

    /**
     * Resolve a model identifier to its rate card, or {@link ModelRates#ZERO} if
     * the model isn't a known Anthropic family. Null-safe.
     */
    public static ModelRates rates(String model) {
        if (model == null) return ModelRates.ZERO;
        var m = model.toLowerCase();
        // Guarded patterns let the switch match by prefix instead of exact value,
        // which keeps the code working as Anthropic ships new minor revisions.
        return switch (m) {
            case String s when s.startsWith("claude-opus-")   -> OPUS_4;
            case String s when s.startsWith("claude-sonnet-") -> SONNET_4;
            case String s when s.startsWith("claude-haiku-")  -> HAIKU_4;
            default -> ModelRates.ZERO;
        };
    }

    /** True iff the model resolves to a non-zero Anthropic rate card. */
    public static boolean isKnown(String model) {
        return rates(model) != ModelRates.ZERO;
    }

    /**
     * Convert one message's token counts into a USD {@link Cost}, broken into the
     * four billing buckets. Cache writes at the two TTLs are summed into a single
     * {@code cacheWrite} field on the result; the per-tier split lives only on
     * the input {@link MessageUsage}.
     */
    public static Cost cost(MessageUsage u) {
        var r = rates(u.model());
        double input  = u.inputTokens()  * r.input()  / 1_000_000.0;
        double output = u.outputTokens() * r.output() / 1_000_000.0;
        double cw     = u.cacheCreate5mTokens() * r.cacheWrite5m() / 1_000_000.0
                      + u.cacheCreate1hTokens() * r.cacheWrite1h() / 1_000_000.0;
        double cr     = u.cacheReadTokens() * r.cacheRead() / 1_000_000.0;
        return new Cost(input, output, cw, cr);
    }
}
