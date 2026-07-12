package com.github.stevestorey.claudeanalyser.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * One Claude Code session — a single {@code .jsonl} file under
 * {@code ~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl} reduced to just the
 * billable parts.
 *
 * <p>{@code projectPath} is the encoded directory name (e.g.
 * {@code -opt-projects-paris}) the session was originally recorded against; it's
 * only used as a human-readable label in reports.
 *
 * <p>{@code title} is the auto-generated session description from the file's
 * {@code ai-title} lines (the last one wins — the CLI regenerates titles as the
 * conversation evolves); {@code null} when the session has none.
 *
 * <p>{@code usages} contains exactly one entry per assistant message; non-assistant
 * lines (user messages, system events, summaries…) are dropped during parsing.
 * The list may be empty for a session that contained no assistant turns.
 */
public record Session(
        String sessionId,
        Path file,
        String projectPath,
        String title,
        List<MessageUsage> usages) {

    /** Earliest assistant-message timestamp, or empty if the session has no usages. */
    public Optional<Instant> firstTimestamp() {
        return usages.stream().map(MessageUsage::timestamp).min(Instant::compareTo);
    }

    /** Latest assistant-message timestamp, or empty if the session has no usages. */
    public Optional<Instant> lastTimestamp() {
        return usages.stream().map(MessageUsage::timestamp).max(Instant::compareTo);
    }
}
