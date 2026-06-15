package com.platform.tools.aireview.cache;

import com.platform.tools.aireview.review.Finding;
import com.platform.tools.aireview.review.ReviewResult;
import com.platform.tools.aireview.util.Hashing;
import com.platform.tools.aireview.util.Json;
import com.platform.tools.aireview.util.Logs;
import com.platform.tools.aireview.util.Version;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** File cache keyed on the review inputs. Never throws into the review path. */
public final class ReviewCache {

    private final Path dir = Path.of(System.getProperty("user.home"), ".cache", "aireview");
    private final boolean enabled;
    private final long ttlHours;

    public ReviewCache(boolean enabled, long ttlHours) {
        this.enabled = enabled;
        this.ttlHours = ttlHours;
    }

    /** Cache key per SPEC §10.4. */
    public String key(String diff, String intent, String rubric, String model) {
        return Hashing.sha256(diff, intent, rubric, model, Version.PROMPT_TEMPLATE_VERSION);
    }

    /** Stored shape: summary + findings only (the durable part of a result). */
    private record Entry(String summary, List<Finding> findings) {}

    public ReviewResult get(String key) {
        if (!enabled) return null;
        try {
            Path f = dir.resolve(key + ".json");
            if (!Files.isRegularFile(f)) return null;
            Instant mtime = Files.getLastModifiedTime(f).toInstant();
            if (Duration.between(mtime, Instant.now()).toHours() >= ttlHours) {
                Files.deleteIfExists(f);
                return null;
            }
            Entry e = Json.MAPPER.readValue(Files.readString(f), Entry.class);
            ReviewResult rr = new ReviewResult();
            rr.summary = e.summary() == null ? "" : e.summary();
            rr.findings = e.findings() == null ? List.of() : e.findings();
            rr.fromCache = true;
            return rr;
        } catch (Exception ex) {
            Logs.warn("cache read failed: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    public void put(String key, ReviewResult rr) {
        if (!enabled || rr == null) return;
        try {
            Files.createDirectories(dir);
            Entry e = new Entry(rr.summary, rr.findings);
            Files.writeString(dir.resolve(key + ".json"), Json.MAPPER.writeValueAsString(e));
        } catch (Exception ex) {
            Logs.warn("cache write failed: " + ex.getClass().getSimpleName());
        }
    }
}
