package com.platform.tools.aireview.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Tiny append-only local logger at ~/.cache/aireview/aireview.log.
 * Never throws. Never logs diff content or secrets. Best-effort size-based rotation.
 */
public final class Logs {
    private static final Path LOG = Path.of(System.getProperty("user.home"),
            ".cache", "aireview", "aireview.log");
    private static final long MAX_BYTES = 1_000_000L; // ~1MB then rotate once
    private static volatile boolean debug = false;

    private Logs() {}

    public static void setDebug(boolean on) { debug = on; }
    public static boolean debug() { return debug; }

    public static synchronized void info(String msg) { write("INFO", msg); }
    public static synchronized void warn(String msg) { write("WARN", msg); }
    public static synchronized void error(String msg, Throwable t) {
        write("ERROR", msg + (t == null ? "" : " :: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    private static void write(String level, String msg) {
        try {
            Files.createDirectories(LOG.getParent());
            rotateIfNeeded();
            String line = Instant.now() + " " + level + " " + msg + System.lineSeparator();
            Files.write(LOG, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never break the review path
        }
    }

    private static void rotateIfNeeded() throws IOException {
        if (Files.exists(LOG) && Files.size(LOG) > MAX_BYTES) {
            Path bak = LOG.resolveSibling("aireview.log.1");
            Files.deleteIfExists(bak);
            Files.move(LOG, bak);
        }
    }
}
