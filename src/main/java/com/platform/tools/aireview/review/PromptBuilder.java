package com.platform.tools.aireview.review;

import java.util.List;

/** Assembles the system + user messages per SPEC §11. */
public final class PromptBuilder {
    private PromptBuilder() {}

    public static String buildSystem(String agentsMd, String bestPractices) {
        return """
            You are a senior Java / Spring Boot reviewer performing an ADVISORY push-time review.

            CONTEXT — repository knowledge (AGENTS.md):
            %s

            CONTEXT — engineering best practices:
            %s

            SCOPE — what NOT to do (handled by other tooling):
            - Do NOT report formatting/style, lint, compilation, test presence, or secret/
              security-scan findings. Linting, tests, and security scanning are already enforced.
            - Do NOT restate the diff or summarize what the code does unless it reveals a problem.

            FOCUS — review for:
            1. Design & abstraction quality; Spring idioms (layering, DI, transactions, bean scope).
            2. Logic correctness & edge cases (null handling, Optional misuse, concurrency,
               JPA N+1 / lazy-loading, transaction boundaries / self-invocation).
            3. Intent match: does the change accomplish what the commit messages claim?
            4. Duplication VISIBLE in this diff or in the provided "possibly-related existing code".
            5. Clarity & maintainability of the changed code only.

            RULES:
            - Review ONLY the changed lines and their immediate context. Do not invent code you
              cannot see.
            - Prefer few high-confidence findings over many speculative ones; false positives
              erode trust.
            - If you have no material findings, return an empty findings array.

            OUTPUT — respond with ONLY valid JSON matching this schema (no prose, no code fences):
            {
              "summary": "string",
              "findings": [
                {
                  "severity": "critical|major|minor|info",
                  "file": "string",
                  "line": 0,
                  "title": "string",
                  "detail": "string",
                  "suggestion": "string or null"
                }
              ]
            }
            """.formatted(blankToNone(agentsMd), blankToNone(bestPractices));
    }

    public static String buildUser(String intent, List<String> grepHits, String diff) {
        String hits = (grepHits == null || grepHits.isEmpty())
                ? "none"
                : String.join("\n", grepHits);
        return """
            COMMIT MESSAGES (intent):
            %s

            POSSIBLY-RELATED EXISTING CODE (heuristic, may be irrelevant):
            %s

            DIFF (unified):
            %s
            """.formatted(blankToNone(intent), hits, diff == null ? "" : diff);
    }

    private static String blankToNone(String s) {
        return (s == null || s.isBlank()) ? "(none provided)" : s.strip();
    }
}
