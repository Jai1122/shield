package com.platform.tools.aireview.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single review observation as returned by the model. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(
        String severity,   // critical | major | minor | info
        String file,
        Integer line,      // nullable
        String title,
        String detail,
        String suggestion) {

    public Severity sev() { return Severity.from(severity); }

    public enum Severity {
        CRITICAL(3), MAJOR(2), MINOR(1), INFO(0);
        public final int rank;
        Severity(int r) { this.rank = r; }

        public static Severity from(String s) {
            if (s == null) return INFO;
            return switch (s.trim().toLowerCase()) {
                case "critical" -> CRITICAL;
                case "major" -> MAJOR;
                case "minor" -> MINOR;
                default -> INFO;
            };
        }
    }
}
