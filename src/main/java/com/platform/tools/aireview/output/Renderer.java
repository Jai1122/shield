package com.platform.tools.aireview.output;

import com.platform.tools.aireview.config.Config;
import com.platform.tools.aireview.review.Finding;
import com.platform.tools.aireview.review.ReviewResult;
import com.platform.tools.aireview.util.Ansi;
import com.platform.tools.aireview.util.Json;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;

/**
 * Renders results to stderr so it never pollutes stdout consumers and never affects the push.
 */
public final class Renderer {

    private final Config.Output cfg;
    private final PrintStream out = System.err;

    public Renderer(Config.Output cfg) { this.cfg = cfg; }

    public void render(ReviewResult rr) {
        if ("json".equalsIgnoreCase(cfg.format)) {
            try { out.println(Json.MAPPER.writeValueAsString(rr)); } catch (Exception ignored) {}
            return;
        }

        Finding.Severity min = Finding.Severity.from(cfg.minSeverity);
        List<Finding> shown = rr.findings.stream()
                .filter(f -> f.sev().rank >= min.rank)
                .sorted(Comparator.comparingInt((Finding f) -> -f.sev().rank)
                        .thenComparing(f -> f.file() == null ? "" : f.file()))
                .toList();

        String secs = String.format("%.1fs", rr.elapsedMs / 1000.0);
        String cacheTag = rr.fromCache ? " (cached)" : "";
        out.println();
        out.println(Ansi.dim("─ ") + Ansi.bold("aireview") + Ansi.dim(" ─ advisory review (range "
                + rr.rangeLabel + ", " + rr.filesChanged + " files, " + rr.linesChanged
                + " lines) ─ " + secs + cacheTag + " ─"));

        if (rr.summary != null && !rr.summary.isBlank()) {
            out.println();
            out.println("Summary: " + rr.summary.strip());
        }
        if (rr.truncated) {
            out.println(Ansi.yellow("  (model output was truncated — consider raising maxOutputTokens)"));
        }

        if (shown.isEmpty()) {
            out.println();
            out.println(Ansi.green("aireview: no material findings. ✔  (" + secs + ")"));
            out.println();
            return;
        }

        out.println();
        for (Finding f : shown) {
            out.println("  " + bullet(f.sev()) + "  " + loc(f) + "  " + safe(f.title()));
            if (f.detail() != null && !f.detail().isBlank()) {
                out.println("     " + indent(f.detail().strip()));
            }
            if (f.suggestion() != null && !f.suggestion().isBlank()) {
                out.println("     " + Ansi.cyan("↳ Suggestion: ") + indent(f.suggestion().strip()));
            }
            out.println();
        }

        long crit = count(shown, Finding.Severity.CRITICAL);
        long maj = count(shown, Finding.Severity.MAJOR);
        long minr = count(shown, Finding.Severity.MINOR);
        long info = count(shown, Finding.Severity.INFO);
        out.println(shown.size() + " findings (" + crit + " critical, " + maj + " major, "
                + minr + " minor, " + info + " info).  "
                + Ansi.dim("Advisory only — push proceeds."));
        out.println();
    }

    private static long count(List<Finding> fs, Finding.Severity s) {
        return fs.stream().filter(f -> f.sev() == s).count();
    }

    private String bullet(Finding.Severity s) {
        String dot = "●";
        return switch (s) {
            case CRITICAL -> Ansi.red(dot + " CRIT");
            case MAJOR -> Ansi.red(dot + " MAJOR");
            case MINOR -> Ansi.yellow(dot + " MINOR");
            case INFO -> Ansi.dim(dot + " INFO");
        };
    }

    private String loc(Finding f) {
        String file = f.file() == null || f.file().isBlank() ? "(unknown)" : f.file();
        return Ansi.bold(file + (f.line() == null ? "" : ":" + f.line()));
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String indent(String s) { return s.replace("\n", "\n     "); }
}
