package com.github.stevestorey.claudeanalyser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionParserTest {

    @Test void parsesAssistantUsageAndIgnoresOthers(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("abc.jsonl");
        String jsonl = """
                {"type":"user","timestamp":"2026-05-09T12:00:00Z","sessionId":"abc"}
                {"type":"assistant","timestamp":"2026-05-09T12:00:01Z","sessionId":"abc","message":{"model":"claude-opus-4-7","usage":{"input_tokens":100,"output_tokens":200,"cache_read_input_tokens":50,"cache_creation":{"ephemeral_5m_input_tokens":10,"ephemeral_1h_input_tokens":5}}}}
                {"type":"assistant","timestamp":"2026-05-09T12:00:02Z","sessionId":"abc","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":1,"output_tokens":2,"cache_creation_input_tokens":7}}}
                bogus line not json
                """;
        Files.writeString(f, jsonl);

        var s = new SessionParser().parse(f);
        assertThat(s.sessionId()).isEqualTo("abc");
        assertThat(s.usages()).hasSize(2);

        var first = s.usages().get(0);
        assertThat(first.model()).isEqualTo("claude-opus-4-7");
        assertThat(first.inputTokens()).isEqualTo(100);
        assertThat(first.outputTokens()).isEqualTo(200);
        assertThat(first.cacheReadTokens()).isEqualTo(50);
        assertThat(first.cacheCreate5mTokens()).isEqualTo(10);
        assertThat(first.cacheCreate1hTokens()).isEqualTo(5);

        var second = s.usages().get(1);
        // fallback: flat cache_creation_input_tokens treated as 5m
        assertThat(second.cacheCreate5mTokens()).isEqualTo(7);
        assertThat(second.cacheCreate1hTokens()).isZero();
    }

    @Test void picksUpLastAiTitle(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("abc.jsonl");
        Files.writeString(f, """
                {"type":"ai-title","aiTitle":"First title","sessionId":"abc"}
                {"type":"ai-title","aiTitle":"Refined title","sessionId":"abc"}
                """);

        assertThat(new SessionParser().parse(f).title()).isEqualTo("Refined title");
    }

    @Test void subagentFileGetsProjectFromGrandparentAndTitleFromMetaJson(
            @org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Subagent layout: <project>/<parentSessionId>/subagents/agent-*.jsonl
        Path dir = tmp.resolve("-opt-projects-paris/83394898-cc13/subagents");
        Files.createDirectories(dir);
        Path f = dir.resolve("agent-a8fc806df9076f57f.jsonl");
        Files.writeString(f, """
                {"type":"assistant","timestamp":"2026-05-09T12:00:01Z","message":{"model":"claude-opus-4-7","usage":{"input_tokens":1,"output_tokens":2}}}
                """);
        Files.writeString(dir.resolve("agent-a8fc806df9076f57f.meta.json"),
                """
                {"agentType":"statusline-setup","description":"Add colors to status line","toolUseId":"toolu_01"}
                """);

        var s = new SessionParser().parse(f);
        assertThat(s.projectPath()).isEqualTo("-opt-projects-paris");
        assertThat(s.title()).isEqualTo("Add colors to status line");
    }
}
