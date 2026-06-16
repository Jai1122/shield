package com.platform.tools.aireview.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Version {
    private Version() {}

    /** Build version baked in at build time (see build.gradle writeVersion). */
    public static String get() {
        try (InputStream in = Version.class.getResourceAsStream("/aireview-version.txt")) {
            if (in == null) return "dev";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "dev";
        }
    }

    /** Prompt template version — participates in the cache key. Bump on prompt changes. */
    public static final String PROMPT_TEMPLATE_VERSION = "p2";
}
