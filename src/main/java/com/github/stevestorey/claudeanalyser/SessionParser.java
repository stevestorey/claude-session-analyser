package com.github.stevestorey.claudeanalyser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.stevestorey.claudeanalyser.model.MessageUsage;
import com.github.stevestorey.claudeanalyser.model.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses a Claude Code session JSONL file into a {@link Session}, keeping only the
 * fields needed to compute cost. Designed to be tolerant: each line is parsed
 * independently, and any line that's malformed, blank, or not an assistant
 * message is silently skipped — sessions in the wild contain many non-message
 * line types ({@code user}, {@code system}, {@code summary}, {@code attachment},
 * {@code permission-mode}, {@code file-history-snapshot}, …) that we just don't care about.
 *
 * <p>Cheap-and-fast Jackson tree-model parsing is used rather than a typed POJO
 * so we don't have to model the entire schema — only the path
 * {@code .message.usage}. The class is stateless beyond the {@link ObjectMapper}
 * instance and is safe to reuse across files.
 */
public final class SessionParser {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Parse one JSONL file into a {@link Session}. The session id is derived from
     * the filename (without {@code .jsonl}) and {@code projectPath} from the
     * parent directory name (Claude Code's encoded-cwd label).
     *
     * <p>Throws {@link IOException} only on file-system errors; per-line JSON
     * problems are swallowed so a single bad line doesn't lose the whole session.
     */
    public Session parse(Path file) throws IOException {
        List<MessageUsage> usages = new ArrayList<>();
        // Claude Code writes one JSONL line per content block (thinking / text /
        // tool_use) of a single assistant response, stamping the *same* full usage
        // object on each. Counting every line N-times-bills the one API call — so
        // dedupe and count each call once. The key is message.id + requestId: id
        // alone is enough for current data (strict 1:1 with requestId), but pairing
        // in the requestId guards against id reuse across retries/regenerations.
        Set<String> seenCalls = new HashSet<>();
        String sessionId = stripExtension(file.getFileName().toString());
        String projectPath = file.getParent() == null ? "" : file.getParent().getFileName().toString();

        try (Stream<String> lines = Files.lines(file)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                try {
                    JsonNode root = mapper.readTree(line);

                    // Only assistant messages carry token usage; everything else
                    // is conversation/state metadata we don't bill against.
                    if (!"assistant".equals(text(root, "type"))) return;

                    JsonNode message = root.get("message");
                    if (message == null || message.isNull()) return;
                    JsonNode usage = message.get("usage");
                    if (usage == null || usage.isNull()) return;

                    // Top-level requestId; message.id lives on the nested message.
                    String messageId = text(message, "id");
                    if (messageId != null) {
                        String callKey = messageId + '\0' + text(root, "requestId");
                        if (!seenCalls.add(callKey)) return;
                    }

                    String model = text(message, "model");
                    // Top-level timestamp on the JSONL line, not message.created_at.
                    // PlanEstimator relies on this being the wall-clock time of the call.
                    Instant ts = parseInstant(text(root, "timestamp"));
                    if (ts == null) return;

                    long input  = longValue(usage, "input_tokens");
                    long output = longValue(usage, "output_tokens");
                    long cacheRead = longValue(usage, "cache_read_input_tokens");

                    // Cache writes: newer entries break out 5m vs 1h TTL inside
                    // a nested object; older entries only have the flat field —
                    // when that's all we have, treat it as 5m (the cheaper rate)
                    // to avoid over-estimating cost.
                    long cw5m = 0, cw1h = 0;
                    JsonNode cacheCreation = usage.get("cache_creation");
                    if (cacheCreation != null && cacheCreation.isObject()) {
                        cw5m = longValue(cacheCreation, "ephemeral_5m_input_tokens");
                        cw1h = longValue(cacheCreation, "ephemeral_1h_input_tokens");
                    }
                    if (cw5m == 0 && cw1h == 0) {
                        cw5m = longValue(usage, "cache_creation_input_tokens");
                    }

                    usages.add(new MessageUsage(ts, model, input, output, cw5m, cw1h, cacheRead));
                } catch (Exception ignored) {
                    // Skip malformed lines silently — partial files happen and
                    // we'd rather report what we can than fail the whole session.
                }
            });
        }

        return new Session(sessionId, file, projectPath, List.copyOf(usages));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static long longValue(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? 0L : v.asLong(0);
    }

    private static Instant parseInstant(String s) {
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }
}
