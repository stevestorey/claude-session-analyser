package com.github.stevestorey.claudeanalyser;

import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PricingTest {

    @Test void opusInputAndOutput() {
        var u = new MessageUsage(Instant.EPOCH, "claude-opus-4-7", 1_000_000, 1_000_000, 0, 0, 0);
        var c = Pricing.cost(u);
        assertThat(c.input()).isCloseTo(5.00, within(1e-9));
        assertThat(c.output()).isCloseTo(25.00, within(1e-9));
        assertThat(c.total()).isCloseTo(30.00, within(1e-9));
    }

    @Test void sonnetCacheTiers() {
        var u = new MessageUsage(Instant.EPOCH, "claude-sonnet-4-5-20250929",
                0, 0, 1_000_000, 1_000_000, 1_000_000);
        var c = Pricing.cost(u);
        assertThat(c.cacheWrite()).isCloseTo(3.75 + 6.00, within(1e-9));
        assertThat(c.cacheRead()).isCloseTo(0.30, within(1e-9));
    }

    @Test void haikuRates() {
        var u = new MessageUsage(Instant.EPOCH, "claude-haiku-4-5-20251001", 1_000_000, 1_000_000, 0, 0, 0);
        var c = Pricing.cost(u);
        assertThat(c.total()).isCloseTo(6.00, within(1e-9));
    }

    @Test void unknownModelHasZeroCost() {
        var u = new MessageUsage(Instant.EPOCH, "gemma-9b.gguf", 1_000_000, 1_000_000, 0, 0, 0);
        assertThat(Pricing.isKnown(u.model())).isFalse();
        assertThat(Pricing.cost(u).total()).isZero();
    }
}
