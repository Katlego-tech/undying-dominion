package com.undyingdominion.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot proves the public protocol is enough to play, so it may use nothing of the server's: only
 * java.*, org.json and its own package (docs/design/bot.md §2).
 */
class BotIsolationTest {

    @Test
    void theBotImportsNothingFromTheServer() throws IOException {
        Path sources = Path.of("src/main/java/com/undyingdominion/bot");
        List<String> offending = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> listed = Files.list(sources)) {
            files = listed.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty(), "no bot sources found under " + sources.toAbsolutePath());
        for (Path file : files) {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("import ")) continue;
                String imported = trimmed.replaceFirst("import (static )?", "");
                if (!imported.startsWith("java.") && !imported.startsWith("org.json.")
                        && !imported.startsWith("com.undyingdominion.bot.")) {
                    offending.add(file.getFileName() + ": " + trimmed);
                }
            }
        }
        assertTrue(offending.isEmpty(), "the bot reaches into the server: " + offending);
    }
}
