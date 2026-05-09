package com.github.stevestorey.claudeanalyser;

import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import com.github.stevestorey.claudeanalyser.model.Session;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI entry point. Wires the pipeline together:
 *
 * <pre>
 *   SessionScanner.findJsonl  →  SessionParser.parse(*)
 *                              ↓
 *                          List&lt;Session&gt;
 *                              ↓
 *                       Report.aggregateByModel
 *                              ↓
 *                          Report.print
 * </pre>
 *
 * Argument parsing uses picocli; defaults are tuned for "just run it against
 * my actual {@code ~/.claude/projects}" with no flags. See {@code CLAUDE.md}
 * in the repo root for the full set of options.
 */
@Command(
        name = "claude-session-analyser",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = """
                Analyse Claude Code session JSONL files in ~/.claude/projects to
                report token usage and estimated USD cost (Anthropic public list pricing).
                """)
public final class Main implements Callable<Integer> {

    @Option(names = "--root",
            description = "Root directory containing per-project session folders (default: ~/.claude/projects)")
    Path root = Paths.get(System.getProperty("user.home"), ".claude", "projects");

    @Option(names = "--since",
            description = "Only include messages on/after this date (YYYY-MM-DD).")
    String since;

    @Option(names = "--format",
            description = "Output format: table or csv (default: table).")
    String format = "table";

    @Option(names = "--top",
            description = "Show top N sessions by cost in table mode (default: 20).")
    int top = 20;

    @Option(names = "--session",
            description = "Print a detailed breakdown of just this session id, instead of the overall report.")
    String sessionId;

    @Option(names = "--top-messages",
            description = "In --session mode, how many of the priciest messages to list (default: 15).")
    int topMessages = 15;

    public static void main(String[] args) {
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }

    /**
     * picocli invokes this as the command body. Returns a process exit code: 0 on
     * success, 1 if no JSONL files were found under {@link #root}, 2 if a
     * {@code --session} id was given but didn't match.
     */
    @Override
    public Integer call() throws Exception {
        // --since is interpreted in the system zone (i.e. "the start of that local day"),
        // not UTC, so it matches what a user typing "since 2026-04-01" intuitively means.
        Instant sinceTs = since == null ? null
                : LocalDate.parse(since).atStartOfDay(ZoneId.systemDefault()).toInstant();

        List<Path> files = SessionScanner.findJsonl(root);
        if (files.isEmpty()) {
            System.err.println("No .jsonl files found under " + root);
            return 1;
        }

        // Parse every session; keep going on per-file errors so one corrupted
        // session doesn't take down the whole report.
        SessionParser parser = new SessionParser();
        List<Session> sessions = new ArrayList<>(files.size());
        for (Path f : files) {
            try {
                Session s = parser.parse(f);
                if (sinceTs != null) {
                    // Re-build the Session record with only the messages on/after the cutoff;
                    // drop the session entirely if nothing remains.
                    List<MessageUsage> filtered = s.usages().stream()
                            .filter(u -> !u.timestamp().isBefore(sinceTs))
                            .toList();
                    if (filtered.isEmpty()) continue;
                    s = new Session(s.sessionId(), s.file(), s.projectPath(), filtered);
                }
                if (!s.usages().isEmpty()) sessions.add(s);
            } catch (Exception e) {
                System.err.println("WARN: could not parse " + f + ": " + e.getMessage());
            }
        }

        // --session: deep-dive on one session and skip the overall report.
        if (sessionId != null) {
            Session match = sessions.stream()
                    .filter(s -> s.sessionId().equals(sessionId))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                System.err.println("Session not found: " + sessionId);
                System.err.println("(Looked under " + root + "; --since may be excluding it.)");
                return 2;
            }
            Report.printSessionDetail(System.out, match, topMessages);
            return 0;
        }

        var rows = Report.rows(sessions);
        var agg = Report.aggregateByModel(sessions);

        Report.Format fmt = switch (format.toLowerCase()) {
            case "csv"   -> Report.Format.CSV;
            case "table" -> Report.Format.TABLE;
            default -> throw new IllegalArgumentException("Unknown format: " + format);
        };

        var weekly = Report.aggregateByWeek(sessions);
        var monthly = Report.aggregateByMonth(sessions);

        Report.print(System.out, rows, top, fmt,
                agg.byModel(), agg.unknownTokens(), weekly, monthly);
        return 0;
    }
}
