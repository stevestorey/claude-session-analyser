# claude-session-analyser

Java 25 + Maven CLI that walks `~/.claude/projects/**/*.jsonl`, aggregates assistant
token usage, applies Anthropic public list pricing, and reports per-session cost
plus a heuristic estimate of "extra usage" billed on top of a Pro/Team plan.

## Build & run

```bash
mvn test                                          # 7 unit tests
mvn -q -DskipTests package                        # produces target/claude-session-analyser.jar (shaded fat jar)
java -jar target/claude-session-analyser.jar      # default: --plan pro --pro-window-budget 5.00 --top 20 --format table
java -jar target/claude-session-analyser.jar --format csv > usage.csv
java -jar target/claude-session-analyser.jar --plan team --team-monthly-budget 200
java -jar target/claude-session-analyser.jar --plan none --since 2026-04-01
```

Toolchain on this host: Amazon Corretto 25.0.3, Maven 3.9.15.

## Build setup

The pom inherits from `spring-boot-starter-parent:4.0.6` purely for **dependency
management** — this is not a Spring Boot application. Versions for
`jackson-databind`, `junit-jupiter`, and `assertj-core` come from the BOM and
are intentionally not pinned here. Picocli is not managed by Spring Boot, so
its version stays in `<properties>`.

Two pieces of inherited config are overridden:
- `spring-boot-maven-plugin`'s `repackage` execution is bound to phase `none`
  (we are not producing a Spring Boot fat jar).
- `maven-shade-plugin` configuration uses `combine.self="override"` because
  the parent's pluginManagement injects parameters that the stand-alone shade
  plugin (which we use for our runnable fat jar) doesn't recognise.

Notable major-version implications of Spring Boot 4.0.6:
- **Jackson 3.x** — package rename: imports use `tools.jackson.databind.*`,
  not `com.fasterxml.jackson.databind.*`. Also `JsonNode.asText()` is
  deprecated in favour of `asString()`.
- **JUnit Jupiter 6.x** — package names unchanged from 5.x, source-compatible.
- **AssertJ 3.27.x** — unchanged.

## Source layout

All code is in `com.github.stevestorey.claudeanalyser`:

- `Main` — picocli entry point.
- `SessionScanner` — `Files.walk` over the projects root collecting `*.jsonl`.
- `SessionParser` — Jackson-based line-by-line JSONL parsing; tolerant of malformed lines.
- `Pricing` — model-family → `ModelRates` lookup using guarded switch patterns; `cost(MessageUsage)`.
- `PlanEstimator` — sealed `Plan` interface (`Pro`, `Team`, `None`); buckets messages and computes per-bucket overage and per-session overage attribution.
- `Report` — table and CSV rendering, plus `aggregateByModel`.
- `model/` — records: `Session`, `MessageUsage`, `Cost`, `ModelRates`.

Tests in `src/test/java/.../{Pricing,SessionParser,PlanEstimator}Test.java` use JUnit 5 + AssertJ.

## Session JSONL schema (the bits we use)

Files live at `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`. One JSON object per line.
Many `type` values exist (`user`, `assistant`, `system`, `summary`, `attachment`,
`permission-mode`, `file-history-snapshot`, `ai-title`, `last-prompt`); only `assistant`
carries token usage.

For `type == "assistant"`:
- `message.model` — e.g. `claude-opus-4-7`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001`.
- `message.usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`.
- `message.usage.cache_creation.ephemeral_5m_input_tokens` and `ephemeral_1h_input_tokens`
  give the cache-write breakdown by TTL (priced differently). Older entries may only have
  the flat `cache_creation_input_tokens`; the parser treats that as 5m.
- Top-level `timestamp` (ISO-8601, UTC).

`service_tier` is always `"standard"` — **there is no billing-tier flag in the JSONL**,
so any "extra usage" figure is a heuristic, not a read.

Local / non-Anthropic models seen in the data (e.g. `gemma-*.gguf`) get tokens counted
but $0 cost, by design.

## Pricing table (USD per 1M tokens, public list)

Mapping is by family prefix so minor version bumps don't break it.

| family   | input | output | cache write 5m | cache write 1h | cache read |
|----------|------:|-------:|---------------:|---------------:|-----------:|
| Opus 4.x | 15.00 | 75.00  | 18.75          | 30.00          | 1.50       |
| Sonnet 4.x | 3.00 | 15.00 |  3.75          |  6.00          | 0.30       |
| Haiku 4.x  | 1.00 |  5.00 |  1.25          |  2.00          | 0.10       |

If Anthropic publishes new prices, edit `Pricing.java`.

## "Extra usage" estimation

`--plan pro` (default): groups all assistant messages chronologically into rolling
5-hour windows (a new window opens at the first message ≥5h after the previous one).
Cost above `--pro-window-budget` per window is reported as estimated extra usage and
attributed proportionally back to contributing sessions.

`--plan team`: same idea but bucketed by calendar month against `--team-monthly-budget`.

`--plan none`: skip the estimate entirely.

These are estimates. The real billing isn't in the data.

## Conventions in this codebase

- All DTOs are records.
- Sealed interface + pattern-matching switch is the dispatch pattern (see `Pricing.rates`,
  `PlanEstimator.estimate`).
- The shade plugin produces a runnable fat jar; `Main-Class` is set in the manifest.
- Comments are sparse on purpose — only used where the *why* isn't obvious from the code.
