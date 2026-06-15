package com.platform.tools.aireview.privacy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Defence-in-depth secret scrubbing applied to the diff before it leaves the machine.
 * NOT a replacement for the dedicated secret scanner — just reduces accidental exposure.
 */
public final class Redactor {

    private static final String MASK = "«redacted»";

    private record Rule(Pattern pattern, int group) {}

    private final List<Rule> rules;

    public Redactor(List<Pattern> extra) {
        this.rules = new java.util.ArrayList<>(List.of(
                // AWS access key id
                new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), 0),
                // PEM private key blocks
                new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"), 0),
                // bearer tokens in text
                new Rule(Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{12,}"), 0),
                // key=value style secrets
                new Rule(Pattern.compile("(?i)(password|passwd|pwd|secret|api[_-]?key|token)\\s*[=:]\\s*([^\\s\"']{4,})"), 2),
                // JDBC URLs with embedded credentials
                new Rule(Pattern.compile("(?i)(jdbc:[a-z]+://)([^\\s:@/]+:[^\\s@/]+)@"), 2),
                // long base64-ish blobs that look like tokens (conservative length)
                new Rule(Pattern.compile("\\b[A-Za-z0-9+/]{40,}={0,2}\\b"), 0)
        ));
        if (extra != null) for (Pattern p : extra) this.rules.add(new Rule(p, 0));
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : rules) {
            var m = r.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                if (r.group() == 0) {
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(MASK));
                } else {
                    // replace only the captured secret group, keep surrounding text
                    String whole = m.group();
                    String secret = m.group(r.group());
                    String replaced = whole.substring(0, m.start(r.group()) - m.start())
                            + MASK
                            + whole.substring(m.end(r.group()) - m.start());
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replaced));
                }
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }
}
