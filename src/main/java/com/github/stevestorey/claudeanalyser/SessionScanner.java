package com.github.stevestorey.claudeanalyser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locates session JSONL files on disk. Claude Code stores one session per file
 * under {@code ~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl}, possibly
 * with sibling subdirectories (e.g. {@code memory/}, sub-agent traces) that
 * also contain {@code .jsonl} files we want to include — hence the recursive walk.
 */
public final class SessionScanner {

    private SessionScanner() {}

    /**
     * Recursively collect every regular {@code *.jsonl} file under {@code root}.
     * Returns an empty list (not an exception) if {@code root} doesn't exist or
     * isn't a directory, so the caller can treat "no data" uniformly.
     */
    public static List<Path> findJsonl(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        }
    }
}
