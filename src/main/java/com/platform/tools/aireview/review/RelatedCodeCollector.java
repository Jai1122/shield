package com.platform.tools.aireview.review;

import com.platform.tools.aireview.config.Config;
import com.platform.tools.aireview.git.GitService;
import com.platform.tools.aireview.privacy.Redactor;

import java.util.List;

/**
 * L1 "related code": gathers the full post-change body of each changed file as read-only review
 * context (see ROADMAP). Budget-driven and fail-soft — a body that can't be read is skipped (the
 * diff still shows the change), and oversized / over-budget bodies degrade to a signatures-only
 * skeleton rather than being dropped. Bodies are redacted before they leave the machine.
 */
public final class RelatedCodeCollector {
    private RelatedCodeCollector() {}

    /**
     * @return a labelled block of file bodies to embed in the prompt, or "" if nothing to include.
     *         Never throws.
     */
    public static String collect(GitService git, String tip, List<String> changedFiles,
                                 Config.RelatedCode rc, Redactor redactor, boolean redact) {
        if (rc == null || !rc.enabled || changedFiles == null || changedFiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int total = 0, included = 0, omitted = 0;
        for (String path : changedFiles) {
            if (included >= rc.maxFiles) { omitted++; continue; }
            String body;
            try {
                body = git.fileAtCommit(tip, path);
            } catch (Exception e) {
                body = null;
            }
            if (body == null || body.isBlank()) continue; // binary/unreadable — diff still shows it

            String safe = redact ? redactor.redact(body) : body;
            String content = safe;
            String tag = "";
            if (content.length() > rc.maxFileChars) {
                content = signatures(safe);
                tag = " (signatures only — file too large)";
            }
            if (total + content.length() > rc.maxTotalChars) {
                if (tag.isEmpty()) {                 // not yet reduced — try skeleton
                    content = signatures(safe);
                    tag = " (signatures only — context budget)";
                }
                if (total + content.length() > rc.maxTotalChars) { omitted++; continue; }
            }
            sb.append("// ===== ").append(path).append(tag).append(" =====\n")
              .append(content).append("\n\n");
            total += content.length();
            included++;
        }
        if (included == 0) return "";
        if (omitted > 0) sb.append("// (").append(omitted)
                .append(" more changed file(s) omitted — context budget reached)\n");
        return sb.toString().strip();
    }

    /**
     * Reduce Java source to a signatures-only skeleton: keep package/imports/annotations, type and
     * field declarations, and method signatures; replace each method/block body with a placeholder.
     * Brace-depth heuristic (does not parse string/char literals) — adequate for read-only context.
     */
    static String signatures(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int depth = 0;
        boolean skipping = false;
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
                if (depth == 2) { out.append("{ /* … */ "); skipping = true; continue; }
            } else if (ch == '}') {
                if (depth > 0) depth--;
                if (depth == 1) { out.append('}'); skipping = false; continue; }
            }
            if (!skipping) out.append(ch);
        }
        return out.toString();
    }
}
