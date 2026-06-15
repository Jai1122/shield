package com.platform.tools.aireview.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.tools.aireview.util.Json;
import com.platform.tools.aireview.util.Logs;

import java.util.ArrayList;
import java.util.List;

/** Defensively turns a (possibly messy) model string into a {@link ReviewResult}. */
public final class ResponseParser {
    private ResponseParser() {}

    /** @return parsed result, or null if no usable JSON could be recovered. */
    public static ReviewResult parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = extractJsonObject(raw);
        if (json == null) {
            Logs.warn("no JSON object found in model response");
            return null;
        }
        try {
            JsonNode root = Json.MAPPER.readTree(json);
            ReviewResult rr = new ReviewResult();
            if (root.hasNonNull("summary")) rr.summary = root.get("summary").asText("");
            JsonNode arr = root.get("findings");
            List<Finding> findings = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    Finding f = coerce(n);
                    if (f != null) findings.add(f);
                }
            }
            rr.findings = findings;
            return rr;
        } catch (Exception e) {
            Logs.warn("failed to parse review JSON: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static Finding coerce(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        String severity = text(n, "severity", "info");
        String file = text(n, "file", "");
        Integer line = n.hasNonNull("line") && n.get("line").isInt() ? n.get("line").asInt() : null;
        String title = text(n, "title", "");
        String detail = text(n, "detail", "");
        String suggestion = n.hasNonNull("suggestion") ? n.get("suggestion").asText() : null;
        if (title.isBlank() && detail.isBlank()) return null; // skip empty findings
        return new Finding(severity, file, line, title, detail, suggestion);
    }

    private static String text(JsonNode n, String field, String def) {
        return n.hasNonNull(field) ? n.get(field).asText(def) : def;
    }

    /** Extract the first balanced top-level JSON object, ignoring braces inside strings. */
    static String extractJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        boolean inStr = false, esc = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced
    }
}
