package com.github.stevestorey.claudeanalyser;

import com.github.stevestorey.claudeanalyser.model.Cost;
import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import com.github.stevestorey.claudeanalyser.model.Session;

import java.io.PrintStream;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Renders the analysis to a {@link PrintStream} in either a human-readable table
 * or a machine-readable CSV. Pure presentation: all costing/aggregation has been
 * done by {@link Pricing} and {@link PlanEstimator} by the time we get here.
 *
 * <p>The flow a caller uses is:
 * <ol>
 *   <li>Build per-session rows with {@link #rows(List, Map)}.</li>
 *   <li>Aggregate token totals by model with {@link #aggregateByModel(List)}.</li>
 *   <li>Hand both to {@link #print} along with the {@link PlanEstimator.Result}.</li>
 * </ol>
 */
public final class Report {

    /** Output format — chosen by the user via {@code --format table|csv}. */
    public enum Format { TABLE, CSV }

    /**
     * One row in the report: aggregated tokens and cost for a single session,
     * plus the (estimated) extra-usage dollars attributed to that session by
     * {@link PlanEstimator}.
     */
    public record SessionRow(
            String sessionId,
            String projectPath,
            Instant first,
            Instant last,
            int messages,
            long inputTokens,
            long outputTokens,
            long cacheWriteTokens,
            long cacheReadTokens,
            Cost cost,
            double estimatedExtraUsage) {}

    private Report() {}

    /**
     * Build per-session rows from the parsed sessions, attaching the overage
     * dollars from a previously-run {@link PlanEstimator}. Rows come back
     * sorted by total cost descending — convenient for "top N priciest"
     * displays.
     */
    public static List<SessionRow> rows(List<Session> sessions, Map<String, Double> overageBySession) {
        List<SessionRow> rows = new ArrayList<>();
        for (Session s : sessions) {
            long in = 0, out = 0, cw = 0, cr = 0;
            Cost cost = Cost.ZERO;
            for (MessageUsage u : s.usages()) {
                in += u.inputTokens();
                out += u.outputTokens();
                cw += u.cacheCreate5mTokens() + u.cacheCreate1hTokens();
                cr += u.cacheReadTokens();
                cost = cost.plus(Pricing.cost(u));
            }
            rows.add(new SessionRow(
                    s.sessionId(),
                    s.projectPath(),
                    s.firstTimestamp().orElse(null),
                    s.lastTimestamp().orElse(null),
                    s.usages().size(),
                    in, out, cw, cr,
                    cost,
                    overageBySession.getOrDefault(s.sessionId(), 0.0)));
        }
        rows.sort(Comparator.comparingDouble((SessionRow r) -> r.cost().total()).reversed());
        return rows;
    }

    /**
     * Render the report. CSV mode emits one row per session and ignores plan and
     * model-aggregate info (those are summarised already inline on each row).
     * Table mode emits a multi-section human-readable summary plus a top-N table.
     *
     * @param top                number of priciest sessions to show in table mode
     * @param plan               the result of {@link PlanEstimator#estimate}
     * @param planType           the {@link PlanEstimator.Plan} that produced {@code plan}
     *                           (used only to label the section header)
     * @param tokensByModel      output of {@link #aggregateByModel}: model name →
     *                           [input, output, cache-write, cache-read]
     * @param unknownModelTokens total tokens charged at $0 because the model
     *                           wasn't a known Anthropic family (e.g. local gguf models)
     */
    public static void print(PrintStream out,
                             List<SessionRow> allRows,
                             int top,
                             Format format,
                             PlanEstimator.Result plan,
                             PlanEstimator.Plan planType,
                             Map<String, long[]> tokensByModel,
                             long unknownModelTokens,
                             List<PeriodRow> weekly,
                             List<PeriodRow> monthly) {
        switch (format) {
            case CSV   -> printCsv(out, allRows, weekly, monthly);
            case TABLE -> printTable(out, allRows, top, plan, planType,
                                     tokensByModel, unknownModelTokens, weekly, monthly);
        }
    }

    private static void printCsv(PrintStream out, List<SessionRow> rows,
                                 List<PeriodRow> weekly, List<PeriodRow> monthly) {
        // Multi-section CSV: each block has its own header line, separated by a
        // blank line and a `# section` marker. Most CSV consumers (pandas with
        // comment='#', csvkit) skip comment rows; for vanilla readers, just split
        // on blank lines.
        out.println("# section: sessions");
        out.println("session_id,project,first_ts,last_ts,messages,input_tokens,output_tokens,cache_write_tokens,cache_read_tokens,cost_input_usd,cost_output_usd,cost_cache_write_usd,cost_cache_read_usd,total_cost_usd,estimated_extra_usage_usd");
        for (SessionRow r : rows) {
            out.printf("%s,%s,%s,%s,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    r.sessionId(), csv(r.projectPath()),
                    r.first(), r.last(),
                    r.messages(),
                    r.inputTokens(), r.outputTokens(), r.cacheWriteTokens(), r.cacheReadTokens(),
                    r.cost().input(), r.cost().output(), r.cost().cacheWrite(), r.cost().cacheRead(),
                    r.cost().total(), r.estimatedExtraUsage());
        }

        out.println();
        out.println("# section: weekly");
        printPeriodCsv(out, "iso_week", weekly);

        out.println();
        out.println("# section: monthly");
        printPeriodCsv(out, "year_month", monthly);
    }

    private static void printPeriodCsv(PrintStream out, String labelHeader, List<PeriodRow> rows) {
        out.println(labelHeader + ",sessions,messages,input_tokens,output_tokens,cache_write_tokens,cache_read_tokens,cost_input_usd,cost_output_usd,cost_cache_write_usd,cost_cache_read_usd,total_cost_usd");
        for (PeriodRow r : rows) {
            out.printf("%s,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    r.label(), r.sessions(), r.messages(),
                    r.inputTokens(), r.outputTokens(), r.cacheWriteTokens(), r.cacheReadTokens(),
                    r.cost().input(), r.cost().output(),
                    r.cost().cacheWrite(), r.cost().cacheRead(),
                    r.cost().total());
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static void printTable(PrintStream out, List<SessionRow> rows, int top,
                                   PlanEstimator.Result plan,
                                   PlanEstimator.Plan planType,
                                   Map<String, long[]> tokensByModel,
                                   long unknownModelTokens,
                                   List<PeriodRow> weekly,
                                   List<PeriodRow> monthly) {
        long totalIn = 0, totalOut = 0, totalCw = 0, totalCr = 0;
        Cost totalCost = Cost.ZERO;
        for (SessionRow r : rows) {
            totalIn += r.inputTokens();
            totalOut += r.outputTokens();
            totalCw += r.cacheWriteTokens();
            totalCr += r.cacheReadTokens();
            totalCost = totalCost.plus(r.cost());
        }

        out.println();
        out.println("=== Claude Session Analyser ===");
        out.printf("Sessions analysed:        %,d%n", rows.size());
        out.printf("Total input tokens:       %,15d%n", totalIn);
        out.printf("Total output tokens:      %,15d%n", totalOut);
        out.printf("Total cache-write tokens: %,15d%n", totalCw);
        out.printf("Total cache-read tokens:  %,15d%n", totalCr);
        out.printf("Estimated total cost:     %15s (Anthropic public list pricing)%n", money(totalCost.total()));
        out.printf("  - input:                %15s%n", money(totalCost.input()));
        out.printf("  - output:               %15s%n", money(totalCost.output()));
        out.printf("  - cache write:          %15s%n", money(totalCost.cacheWrite()));
        out.printf("  - cache read:           %15s%n", money(totalCost.cacheRead()));
        if (unknownModelTokens > 0) {
            out.printf("Tokens from unknown / non-Anthropic models (cost = $0): %,d%n", unknownModelTokens);
        }

        out.println();
        out.println("--- Tokens by model ---");
        out.printf("%-40s %15s %15s %15s %15s%n", "model", "input", "output", "cache-write", "cache-read");
        new TreeMap<>(tokensByModel).forEach((m, t) ->
                out.printf("%-40s %,15d %,15d %,15d %,15d%n", m, t[0], t[1], t[2], t[3]));

        if (!weekly.isEmpty()) {
            out.println();
            out.println("--- Per ISO week ---");
            printPeriodTable(out, "week", weekly);
        }
        if (!monthly.isEmpty()) {
            out.println();
            out.println("--- Per calendar month ---");
            printPeriodTable(out, "month", monthly);
        }

        if (!(planType instanceof PlanEstimator.None)) {
            out.println();
            String planName = switch (planType) {
                case PlanEstimator.Pro p -> "Pro plan, $%.2f / 5h-window".formatted(p.windowBudgetUsd());
                case PlanEstimator.Team t -> "Team plan, $%.2f / month".formatted(t.monthlyBudgetUsd());
                case PlanEstimator.None n -> "none";
            };
            out.printf("--- Estimated 'extra usage' (heuristic; %s) ---%n", planName);
            out.printf("Estimated included:      %15s%n", money(plan.totalIncluded()));
            out.printf("Estimated extra usage:   %15s%n", money(plan.totalOverage()));
            out.println("(Estimate only; the JSONL files do not record actual billing.)");
        }

        out.println();
        int n = Math.min(top, rows.size());
        out.printf("--- Top %d sessions by cost ---%n", n);
        out.printf("%-38s %-22s %8s %14s %14s %14s%n",
                "session", "project", "msgs", "tokens", "cost", "est.extra");
        for (int i = 0; i < n; i++) {
            SessionRow r = rows.get(i);
            long tokens = r.inputTokens() + r.outputTokens() + r.cacheWriteTokens() + r.cacheReadTokens();
            out.printf("%-38s %-22s %,8d %,14d %14s %14s%n",
                    r.sessionId(),
                    truncate(r.projectPath(), 22),
                    r.messages(),
                    tokens,
                    money(r.cost().total()),
                    money(r.estimatedExtraUsage()));
        }
        out.println();
    }

    /**
     * Render a deep-dive view of a single session: header, token totals, cost
     * split by category, per-model breakdown within the session, and the top-N
     * priciest individual messages with their timestamps.
     *
     * <p>Always emits to {@code out} as a human-readable table; CSV is not
     * supported for this view because the natural shape is multi-section, not
     * tabular.
     *
     * @param topMessages how many of the priciest individual messages to list
     * @param estimatedExtraUsage the overage dollars attributed to this session
     *                            by {@link PlanEstimator}, or 0 if not applicable
     */
    public static void printSessionDetail(PrintStream out, Session s, int topMessages,
                                          double estimatedExtraUsage) {
        out.println();
        out.println("=== Session detail ===");
        out.printf("Session id:    %s%n", s.sessionId());
        out.printf("Project:       %s%n", s.projectPath());
        out.printf("File:          %s%n", s.file());
        out.printf("First message: %s%n", s.firstTimestamp().map(Instant::toString).orElse("-"));
        out.printf("Last message:  %s%n", s.lastTimestamp().map(Instant::toString).orElse("-"));
        if (s.firstTimestamp().isPresent() && s.lastTimestamp().isPresent()) {
            long secs = s.lastTimestamp().get().getEpochSecond() - s.firstTimestamp().get().getEpochSecond();
            out.printf("Duration:      %s%n", formatDuration(secs));
        }
        out.printf("Messages:      %,d assistant turns%n", s.usages().size());

        // Token totals (split cache-writes by TTL since this is the detail view).
        long input = 0, output = 0, cw5m = 0, cw1h = 0, cr = 0;
        Cost totalCost = Cost.ZERO;
        Map<String, long[]> byModel = new HashMap<>();   // model -> [in, out, cw, cr]
        Map<String, Cost> costByModel = new HashMap<>();
        for (MessageUsage u : s.usages()) {
            input += u.inputTokens();
            output += u.outputTokens();
            cw5m += u.cacheCreate5mTokens();
            cw1h += u.cacheCreate1hTokens();
            cr += u.cacheReadTokens();
            Cost c = Pricing.cost(u);
            totalCost = totalCost.plus(c);
            String m = u.model() == null ? "<unknown>" : u.model();
            long[] t = byModel.computeIfAbsent(m, k -> new long[4]);
            t[0] += u.inputTokens();
            t[1] += u.outputTokens();
            t[2] += u.cacheCreate5mTokens() + u.cacheCreate1hTokens();
            t[3] += u.cacheReadTokens();
            costByModel.merge(m, c, Cost::plus);
        }

        out.println();
        out.println("--- Tokens ---");
        out.printf("Input:                %,15d%n", input);
        out.printf("Output:               %,15d%n", output);
        out.printf("Cache write (5m TTL): %,15d%n", cw5m);
        out.printf("Cache write (1h TTL): %,15d%n", cw1h);
        out.printf("Cache read:           %,15d%n", cr);
        out.printf("Total:                %,15d%n", input + output + cw5m + cw1h + cr);

        out.println();
        out.println("--- Cost (Anthropic public list pricing) ---");
        out.printf("Input cost:           %15s%n", money(totalCost.input()));
        out.printf("Output cost:          %15s%n", money(totalCost.output()));
        out.printf("Cache-write cost:     %15s%n", money(totalCost.cacheWrite()));
        out.printf("Cache-read cost:      %15s%n", money(totalCost.cacheRead()));
        out.printf("Total cost:           %15s%n", money(totalCost.total()));
        if (estimatedExtraUsage > 0) {
            out.printf("Est. extra usage:     %15s  (heuristic; see overall report)%n",
                    money(estimatedExtraUsage));
        }

        out.println();
        out.println("--- Tokens & cost by model ---");
        out.printf("%-40s %12s %12s %12s %12s %12s%n",
                "model", "input", "output", "cache-wr", "cache-rd", "cost");
        new TreeMap<>(byModel).forEach((m, t) ->
                out.printf("%-40s %,12d %,12d %,12d %,12d %12s%n",
                        m, t[0], t[1], t[2], t[3],
                        money(costByModel.getOrDefault(m, Cost.ZERO).total())));

        // Top-N priciest individual messages.
        List<MessageUsage> sorted = new ArrayList<>(s.usages());
        sorted.sort(Comparator.comparingDouble((MessageUsage u) -> Pricing.cost(u).total()).reversed());
        int n = Math.min(topMessages, sorted.size());
        out.println();
        out.printf("--- Top %d most expensive messages ---%n", n);
        out.printf("%-25s %-30s %12s %12s %12s %12s %12s%n",
                "timestamp", "model", "input", "output", "cache-wr", "cache-rd", "cost");
        for (int i = 0; i < n; i++) {
            MessageUsage u = sorted.get(i);
            out.printf("%-25s %-30s %,12d %,12d %,12d %,12d %12s%n",
                    u.timestamp(),
                    truncate(u.model() == null ? "<unknown>" : u.model(), 30),
                    u.inputTokens(), u.outputTokens(),
                    u.cacheCreate5mTokens() + u.cacheCreate1hTokens(),
                    u.cacheReadTokens(),
                    money(Pricing.cost(u).total()));
        }
        out.println();
    }

    private static void printPeriodTable(PrintStream out, String labelHeader, List<PeriodRow> rows) {
        out.printf("%-10s %8s %10s %15s %15s %15s %15s %12s%n",
                labelHeader, "sessions", "messages", "input", "output", "cache-write", "cache-read", "cost");
        for (PeriodRow r : rows) {
            out.printf("%-10s %,8d %,10d %,15d %,15d %,15d %,15d %12s%n",
                    r.label(), r.sessions(), r.messages(),
                    r.inputTokens(), r.outputTokens(),
                    r.cacheWriteTokens(), r.cacheReadTokens(),
                    money(r.cost().total()));
        }
    }

    private static String formatDuration(long secs) {
        if (secs < 0) return "-";
        long h = secs / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        return h > 0 ? "%dh %dm %ds".formatted(h, m, s)
                     : m > 0 ? "%dm %ds".formatted(m, s)
                             : "%ds".formatted(s);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String money(double v) {
        return "$%,.2f".formatted(v);
    }

    /**
     * Sum tokens across every assistant message in every session, grouped by model.
     *
     * <p>Returned map: model name → 4-element array
     * {@code [inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens]}.
     * Cache-write 5m and 1h are summed together here — the per-tier breakdown
     * isn't useful at the report level. Also returns the count of tokens spent
     * on models we don't recognise as Anthropic families (priced at $0), so the
     * report can surface a "tokens not billed by Anthropic" footnote.
     */
    public static AggregateTokens aggregateByModel(List<Session> sessions) {
        Map<String, long[]> byModel = new HashMap<>();
        long unknown = 0;
        for (Session s : sessions) {
            for (MessageUsage u : s.usages()) {
                String m = u.model() == null ? "<unknown>" : u.model();
                long[] t = byModel.computeIfAbsent(m, k -> new long[4]);
                t[0] += u.inputTokens();
                t[1] += u.outputTokens();
                t[2] += u.cacheCreate5mTokens() + u.cacheCreate1hTokens();
                t[3] += u.cacheReadTokens();
                if (!Pricing.isKnown(m)) {
                    unknown += u.inputTokens() + u.outputTokens()
                             + u.cacheCreate5mTokens() + u.cacheCreate1hTokens()
                             + u.cacheReadTokens();
                }
            }
        }
        return new AggregateTokens(byModel, unknown);
    }

    /** Result of {@link #aggregateByModel}. */
    public record AggregateTokens(Map<String, long[]> byModel, long unknownTokens) {}

    /**
     * One period (a calendar week or month) summarised. {@code sessions} is the
     * count of <em>distinct</em> sessions that had at least one assistant message
     * in this period — a long session can span multiple periods and will be
     * counted in each.
     */
    public record PeriodRow(
            String label,
            int sessions,
            int messages,
            long inputTokens,
            long outputTokens,
            long cacheWriteTokens,
            long cacheReadTokens,
            Cost cost) {}

    /** Aggregate every assistant message into ISO weeks ({@code YYYY-Www}). */
    public static List<PeriodRow> aggregateByWeek(List<Session> sessions) {
        ZoneId zone = ZoneId.systemDefault();
        return aggregateByPeriod(sessions, ts -> {
            var zdt = ts.atZone(zone);
            return "%04d-W%02d".formatted(
                    zdt.get(IsoFields.WEEK_BASED_YEAR),
                    zdt.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        });
    }

    /** Aggregate every assistant message into calendar months ({@code YYYY-MM}). */
    public static List<PeriodRow> aggregateByMonth(List<Session> sessions) {
        ZoneId zone = ZoneId.systemDefault();
        return aggregateByPeriod(sessions, ts -> YearMonth.from(ts.atZone(zone)).toString());
    }

    /**
     * Generic period rollup. {@code labelFn} maps each message timestamp to a
     * sortable string label (e.g. {@code "2026-W19"} or {@code "2026-05"}).
     * Periods come back sorted ascending by label, since the chosen label
     * formats sort lexicographically the same as chronologically.
     */
    private static List<PeriodRow> aggregateByPeriod(List<Session> sessions,
                                                    Function<Instant, String> labelFn) {
        Map<String, long[]> totals = new TreeMap<>();         // [in,out,cw,cr,messages]
        Map<String, double[]> costs = new HashMap<>();         // [in,out,cw,cr]
        Map<String, Set<String>> sessionIds = new HashMap<>(); // distinct session ids per period

        for (Session s : sessions) {
            for (MessageUsage u : s.usages()) {
                String key = labelFn.apply(u.timestamp());
                long[] t = totals.computeIfAbsent(key, k -> new long[5]);
                t[0] += u.inputTokens();
                t[1] += u.outputTokens();
                t[2] += u.cacheCreate5mTokens() + u.cacheCreate1hTokens();
                t[3] += u.cacheReadTokens();
                t[4] += 1;
                Cost c = Pricing.cost(u);
                double[] cArr = costs.computeIfAbsent(key, k -> new double[4]);
                cArr[0] += c.input();
                cArr[1] += c.output();
                cArr[2] += c.cacheWrite();
                cArr[3] += c.cacheRead();
                sessionIds.computeIfAbsent(key, k -> new HashSet<>()).add(s.sessionId());
            }
        }

        List<PeriodRow> rows = new ArrayList<>(totals.size());
        totals.forEach((k, t) -> {
            double[] c = costs.get(k);
            rows.add(new PeriodRow(k, sessionIds.get(k).size(), (int) t[4],
                    t[0], t[1], t[2], t[3],
                    new Cost(c[0], c[1], c[2], c[3])));
        });
        return rows;
    }
}
