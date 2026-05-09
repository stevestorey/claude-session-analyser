package com.github.stevestorey.claudeanalyser;

import com.github.stevestorey.claudeanalyser.model.Cost;
import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import com.github.stevestorey.claudeanalyser.model.Session;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimates how much of the total computed cost would have been billed as
 * "extra usage" on top of a Claude subscription plan.
 *
 * <p><b>This is a heuristic.</b> The session JSONL files do not record actual
 * billing — {@code service_tier} is always {@code "standard"} regardless of
 * whether a request was covered by the plan or paid for à la carte. So the
 * approach here is:
 * <ol>
 *   <li>bucket every assistant message (across all sessions) chronologically;</li>
 *   <li>for each bucket sum the cost we computed via {@link Pricing};</li>
 *   <li>treat the configured per-bucket budget as "included" and the rest as
 *       estimated overage.</li>
 * </ol>
 *
 * <p>Two bucketing strategies are supported via the sealed {@link Plan} hierarchy:
 * Pro uses rolling 5-hour windows (a new window starts whenever ≥5h have passed
 * since the previous message, mirroring how Pro quotas reset); Team uses calendar
 * months. {@link None} short-circuits and returns zeros.
 */
public final class PlanEstimator {

    /** Subscription model used for the extra-usage estimate. */
    public sealed interface Plan permits Pro, Team, None {}
    /** Pro plan: rolling 5-hour windows, with {@code windowBudgetUsd} included per window. */
    public record Pro(double windowBudgetUsd) implements Plan {}
    /** Team plan: calendar-month buckets, with {@code monthlyBudgetUsd} included per month. */
    public record Team(double monthlyBudgetUsd) implements Plan {}
    /** No plan modelling — the estimator returns zero overage. */
    public record None() implements Plan {}

    /** One time bucket (a 5h window or a calendar month) with its cost split into included vs overage. */
    public record Bucket(String label, Instant start, double totalCost, double included, double overage) {}

    /**
     * Aggregate result of an estimate: every bucket, total included/overage,
     * and an attribution map of overage-dollars-per-session that callers use to
     * surface a "this session probably cost you $X extra" figure.
     */
    public record Result(
            List<Bucket> buckets,
            double totalIncluded,
            double totalOverage,
            Map<String, Double> overageBySession) {}

    private PlanEstimator() {}

    /**
     * Run the estimator against {@code sessions} using the chosen {@code plan}.
     * Dispatch is via pattern matching on the sealed {@link Plan} hierarchy.
     */
    public static Result estimate(List<Session> sessions, Plan plan) {
        return switch (plan) {
            case None ignored -> new Result(List.of(), 0, 0, Map.of());
            case Pro p -> estimatePro(sessions, p.windowBudgetUsd());
            case Team t -> estimateTeam(sessions, t.monthlyBudgetUsd());
        };
    }

    /** A single chronological event: which session it belonged to, when it happened, and what it cost. */
    private record Stamped(String sessionId, Instant ts, Cost cost) {}

    /** Flatten every assistant message from every session into one time-ordered stream. */
    private static List<Stamped> flatten(List<Session> sessions) {
        List<Stamped> all = new ArrayList<>();
        for (Session s : sessions) {
            for (MessageUsage u : s.usages()) {
                all.add(new Stamped(s.sessionId(), u.timestamp(), Pricing.cost(u)));
            }
        }
        all.sort(Comparator.comparing(Stamped::ts));
        return all;
    }

    /**
     * Pro plan: walk the time-ordered stream, opening a new 5-hour window whenever
     * the next message is ≥5h after the start of the current window. At each
     * window boundary, close the previous window (compute overage, attribute it
     * back to the contributing sessions proportionally to their share of the
     * window's cost).
     */
    private static Result estimatePro(List<Session> sessions, double budget) {
        List<Stamped> all = flatten(sessions);
        List<Bucket> buckets = new ArrayList<>();
        Map<String, Double> overageBySession = new HashMap<>();

        Duration window = Duration.ofHours(5);
        Instant windowStart = null;
        double windowCost = 0;
        Map<String, Double> windowSessionCost = new HashMap<>();

        for (Stamped st : all) {
            if (windowStart == null || Duration.between(windowStart, st.ts()).compareTo(window) >= 0) {
                if (windowStart != null) {
                    closePro(buckets, overageBySession, windowStart, windowCost, windowSessionCost, budget);
                }
                windowStart = st.ts();
                windowCost = 0;
                windowSessionCost = new HashMap<>();
            }
            double c = st.cost().total();
            windowCost += c;
            windowSessionCost.merge(st.sessionId(), c, Double::sum);
        }
        if (windowStart != null) {
            closePro(buckets, overageBySession, windowStart, windowCost, windowSessionCost, budget);
        }

        double totalIncluded = buckets.stream().mapToDouble(Bucket::included).sum();
        double totalOverage = buckets.stream().mapToDouble(Bucket::overage).sum();
        return new Result(buckets, totalIncluded, totalOverage, overageBySession);
    }

    /**
     * Finalise one window/bucket: record it and split the overage back across
     * the sessions that contributed to it. Attribution is proportional — if a
     * session was 30% of the window's cost it carries 30% of the window's
     * overage. This is a simplification: real billing would attribute by call
     * order within the window, but we don't have a "this call was billed" flag
     * to anchor against, so proportional is the honest approximation.
     */
    private static void closePro(List<Bucket> buckets, Map<String, Double> overageBySession,
                                 Instant start, double cost, Map<String, Double> perSession, double budget) {
        double overage = Math.max(0, cost - budget);
        double included = cost - overage;
        buckets.add(new Bucket(start.toString(), start, cost, included, overage));
        if (overage > 0 && cost > 0) {
            double frac = overage / cost;
            for (var e : perSession.entrySet()) {
                overageBySession.merge(e.getKey(), e.getValue() * frac, Double::sum);
            }
        }
    }

    /**
     * Team plan: bucket by calendar month (system zone), then apply the same
     * "budget = included, rest = overage, attribute overage proportionally"
     * logic used for Pro windows.
     */
    private static Result estimateTeam(List<Session> sessions, double monthlyBudget) {
        List<Stamped> all = flatten(sessions);
        Map<YearMonth, Double> monthCost = new HashMap<>();
        Map<YearMonth, Map<String, Double>> monthSession = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();

        for (Stamped st : all) {
            YearMonth ym = YearMonth.from(st.ts().atZone(zone));
            double c = st.cost().total();
            monthCost.merge(ym, c, Double::sum);
            monthSession
                    .computeIfAbsent(ym, k -> new HashMap<>())
                    .merge(st.sessionId(), c, Double::sum);
        }

        List<Bucket> buckets = new ArrayList<>();
        Map<String, Double> overageBySession = new HashMap<>();
        monthCost.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    YearMonth ym = e.getKey();
                    double cost = e.getValue();
                    double overage = Math.max(0, cost - monthlyBudget);
                    double included = cost - overage;
                    Instant start = ym.atDay(1).atStartOfDay(zone).toInstant();
                    buckets.add(new Bucket(ym.toString(), start, cost, included, overage));
                    if (overage > 0 && cost > 0) {
                        double frac = overage / cost;
                        for (var pe : monthSession.get(ym).entrySet()) {
                            overageBySession.merge(pe.getKey(), pe.getValue() * frac, Double::sum);
                        }
                    }
                });

        double totalIncluded = buckets.stream().mapToDouble(Bucket::included).sum();
        double totalOverage = buckets.stream().mapToDouble(Bucket::overage).sum();
        return new Result(buckets, totalIncluded, totalOverage, overageBySession);
    }
}
