# claude-session-analyser

Java 25 + Maven CLI that walks `~/.claude/projects/**/*.jsonl`, aggregates assistant
token usage, applies Anthropic public list pricing, and reports per-session cost.

## Build & run

```bash
mvn test                                          # unit tests
mvn -q -DskipTests package                        # produces target/claude-session-analyser.jar (shaded fat jar)
java -jar target/claude-session-analyser.jar      # default: --top 20 --format table
java -jar target/claude-session-analyser.jar --format csv > usage.csv
java -jar target/claude-session-analyser.jar --since 2026-04-01
java -jar target/claude-session-analyser.jar --session <sessionId> [--top-messages 15]
```

Detail mode (`--session <id>`) prints a deep-dive for one session: header (file
path, first/last timestamp, duration, message count), token totals with cache
writes split by 5m/1h TTL, cost split by category, per-model breakdown within
the session, and the priciest individual messages. Exit code 2 if the id
doesn't match any session under `--root`.

The default report includes per-ISO-week and per-calendar-month rollups
(distinct-session count, message count, tokens broken by category, and total
cost) computed in the system timezone. Week labels are `YYYY-Www` (ISO 8601
week-based-year), month labels are `YYYY-MM`. CSV mode emits these as extra
sections delimited by `# section: weekly` / `# section: monthly` marker lines
following the per-session block — pandas `read_csv(..., comment='#')` skips
those automatically; vanilla readers can split on the blank lines.

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
- `Report` — table and CSV rendering, plus `aggregateByModel`/`aggregateByWeek`/`aggregateByMonth`.
- `model/` — records: `Session`, `MessageUsage`, `Cost`, `ModelRates`.

Tests in `src/test/java/.../{Pricing,SessionParser}Test.java` use JUnit 5 + AssertJ.

## Session JSONL schema (the bits we use)

Files live at `~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`. One JSON object per line.
Many `type` values exist (`user`, `assistant`, `system`, `summary`, `attachment`,
`permission-mode`, `file-history-snapshot`, `ai-title`, `last-prompt`); only `assistant`
carries token usage.

For `type == "assistant"`:
- `message.model` — e.g. `claude-fable-5`, `claude-opus-4-7`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001`.
- `message.usage.input_tokens`, `output_tokens`, `cache_read_input_tokens`.
- `message.usage.cache_creation.ephemeral_5m_input_tokens` and `ephemeral_1h_input_tokens`
  give the cache-write breakdown by TTL (priced differently). Older entries may only have
  the flat `cache_creation_input_tokens`; the parser treats that as 5m.
- Top-level `timestamp` (ISO-8601, UTC).

A single assistant API response is written as **multiple JSONL lines — one per
content block** (thinking / text / tool_use), each carrying an *identical* full
`message.usage` object. Counting every line N-times-bills the one call, so the
parser dedupes: it counts each `message.id` + top-level `requestId` once. (In
observed data the two are strictly 1:1; the requestId is paired in only to guard
against id reuse across retries.) A 40h session can have ~1,400 assistant lines
collapse to ~600 actual API calls.

`service_tier` is always `"standard"` — there is no billing-tier flag in the JSONL,
so subscription-plan "extra usage" cannot be reliably derived from the data.
The reported cost is always the Anthropic public list price for the tokens used.

**Per-session totals undercount the live `claude` CLI / `/cost`, and that gap is
not a parser bug — it's unrecoverable from the file.** Claude Code makes billable
API calls it never persists as `assistant` turns: conversation **compaction /
summarization** (each re-reads the whole context → large cache-read + a long
summary output) and background **haiku** chores (title generation, topic
classification). None of that usage appears anywhere in the `.jsonl` (verified:
every `*_tokens` field lives on an `assistant` line), so this tool can only ever
report the usage actually recorded in the transcript. A session that shows zero
haiku tokens here may still show a haiku line in the CLI for exactly this reason.

Local / non-Anthropic models seen in the data (e.g. `gemma-*.gguf`) get tokens counted
but $0 cost, by design.

## Pricing table (USD per 1M tokens, public list)

Mapping is by family prefix so minor version bumps don't break it.

| family    | input | output | cache write 5m | cache write 1h | cache read |
|-----------|------:|-------:|---------------:|---------------:|-----------:|
| Fable 5    | 10.00 | 50.00 | 12.50          | 20.00          | 1.00       |
| Opus 4.x   |  5.00 | 25.00 |  6.25          | 10.00          | 0.50       |
| Sonnet 4.x |  3.00 | 15.00 |  3.75          |  6.00          | 0.30       |
| Haiku 4.x  |  1.00 |  5.00 |  1.25          |  2.00          | 0.10       |

Legacy `claude-3-*` ids are also mapped (Opus 3: 15/75, Sonnet 3.x: 3/15,
Haiku 3.5: 0.80/4, Haiku 3: 0.25/1.25, cache rates at the standard 1.25×/2×/0.1×
multipliers) so historical sessions still cost out correctly.

If Anthropic publishes new prices, edit `Pricing.java`.

## Conventions in this codebase

- All DTOs are records.
- Sealed interface + pattern-matching switch is the dispatch pattern (see `Pricing.rates`).
- The shade plugin produces a runnable fat jar; `Main-Class` is set in the manifest.
- Comments are sparse on purpose — only used where the *why* isn't obvious from the code.
