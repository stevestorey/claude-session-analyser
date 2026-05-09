package com.github.stevestorey.claudeanalyser;

import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import com.github.stevestorey.claudeanalyser.model.Session;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PlanEstimatorTest {

    @Test void proWindowSplitsOnFiveHourGap() {
        Instant t0 = Instant.parse("2026-05-01T08:00:00Z");
        // Two opus calls, each 1M input + 1M output = $90 each. Two such calls in
        // a single window -> $180 total, $5 budget -> $175 overage.
        // A third call 6h later starts a new window with $90 cost -> $85 overage.
        var s = session("S1", List.of(
                opus(t0, 1_000_000, 1_000_000),
                opus(t0.plus(1, ChronoUnit.HOURS), 1_000_000, 1_000_000),
                opus(t0.plus(6, ChronoUnit.HOURS), 1_000_000, 1_000_000)
        ));
        var r = PlanEstimator.estimate(List.of(s), new PlanEstimator.Pro(5.00));
        assertThat(r.buckets()).hasSize(2);
        assertThat(r.totalOverage()).isCloseTo(175 + 85, within(1e-6));
        assertThat(r.totalIncluded()).isCloseTo(10.0, within(1e-6));
        assertThat(r.overageBySession()).containsKey("S1");
    }

    @Test void noneReturnsZeroes() {
        var r = PlanEstimator.estimate(List.of(), new PlanEstimator.None());
        assertThat(r.totalOverage()).isZero();
        assertThat(r.totalIncluded()).isZero();
    }

    private static MessageUsage opus(Instant ts, long in, long out) {
        return new MessageUsage(ts, "claude-opus-4-7", in, out, 0, 0, 0);
    }

    private static Session session(String id, List<MessageUsage> us) {
        return new Session(id, Path.of(id), "p", us);
    }
}
