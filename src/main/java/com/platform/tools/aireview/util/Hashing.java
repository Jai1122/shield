package com.platform.tools.aireview.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}

    /** Stable SHA-256 hex of the concatenated parts (joined with a separator). */
    public static String sha256(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((p == null ? "" : p).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1f); // unit separator so parts can't collide
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            // Hashing must never throw in the review path; degrade to a length-based key.
            return "nohash-" + (String.join("|", parts).length());
        }
    }
}
