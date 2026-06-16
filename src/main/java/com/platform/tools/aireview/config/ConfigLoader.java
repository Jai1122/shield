package com.platform.tools.aireview.config;

import com.platform.tools.aireview.util.Logs;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads and merges configuration. Precedence (right wins):
 *   defaults  &lt;  global YAML  &lt;  repo YAML  &lt;  env vars.
 * Never throws: a broken config logs and falls back to whatever merged successfully.
 */
public final class ConfigLoader {

    private static final Path GLOBAL = Path.of(System.getProperty("user.home"),
            ".config", "aireview", "config.yml");

    private ConfigLoader() {}

    /** @param repoRoot nullable repo worktree root for repo-local overrides. */
    public static Config load(Path repoRoot) {
        Config cfg = new Config();
        mergeFile(cfg, GLOBAL);
        if (repoRoot != null) {
            mergeFile(cfg, repoRoot.resolve(".aireview").resolve("config.yml"));
        }
        applyEnvOverrides(cfg);
        validate(cfg);
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static void mergeFile(Config cfg, Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            if (root instanceof Map<?, ?> m) {
                apply(cfg, (Map<String, Object>) m);
            }
        } catch (Exception e) {
            Logs.warn("ignoring unreadable config " + file + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply(Config c, Map<String, Object> m) {
        c.schemaVersion = asInt(m.get("schemaVersion"), c.schemaVersion);

        Map<String, Object> llm = asMap(m.get("llm"));
        if (llm != null) {
            c.llm.provider = asStr(llm.get("provider"), c.llm.provider);
            c.llm.baseUrl = asStr(llm.get("baseUrl"), c.llm.baseUrl);
            c.llm.chatPath = asStr(llm.get("chatPath"), c.llm.chatPath);
            c.llm.model = asStr(llm.get("model"), c.llm.model);
            c.llm.temperature = asDouble(llm.get("temperature"), c.llm.temperature);
            c.llm.maxOutputTokens = asInt(llm.get("maxOutputTokens"), c.llm.maxOutputTokens);
            c.llm.requestTimeoutMs = asInt(llm.get("requestTimeoutMs"), c.llm.requestTimeoutMs);
            c.llm.maxRetries = asInt(llm.get("maxRetries"), c.llm.maxRetries);
            Map<String, Object> auth = asMap(llm.get("auth"));
            if (auth != null) {
                c.llm.auth.scheme = asStr(auth.get("scheme"), c.llm.auth.scheme);
                c.llm.auth.header = asStr(auth.get("header"), c.llm.auth.header);
                c.llm.auth.preEncoded = asBool(auth.get("preEncoded"), c.llm.auth.preEncoded);
            }
        }

        Map<String, Object> rv = asMap(m.get("review"));
        if (rv != null) {
            c.review.trunkBranch = asStr(rv.get("trunkBranch"), c.review.trunkBranch);
            c.review.maxRefsPerPush = asInt(rv.get("maxRefsPerPush"), c.review.maxRefsPerPush);
            c.review.maxCommits = asInt(rv.get("maxCommits"), c.review.maxCommits);
            c.review.maxChangedFiles = asInt(rv.get("maxChangedFiles"), c.review.maxChangedFiles);
            c.review.maxDiffLines = asInt(rv.get("maxDiffLines"), c.review.maxDiffLines);
            c.review.diffContextLines = asInt(rv.get("diffContextLines"), c.review.diffContextLines);
            c.review.maxRubricChars = asInt(rv.get("maxRubricChars"), c.review.maxRubricChars);
            List<String> inc = asStrList(rv.get("reviewableIncludeGlobs"));
            if (inc != null) c.review.reviewableIncludeGlobs = inc;
            List<String> exc = asStrList(rv.get("reviewableExcludeGlobs"));
            if (exc != null) c.review.reviewableExcludeGlobs = exc;
            Map<String, Object> sg = asMap(rv.get("symbolGrep"));
            if (sg != null) {
                c.review.symbolGrep.enabled = asBool(sg.get("enabled"), c.review.symbolGrep.enabled);
                c.review.symbolGrep.maxGrepHits = asInt(sg.get("maxGrepHits"), c.review.symbolGrep.maxGrepHits);
            }
            Map<String, Object> rc = asMap(rv.get("relatedCode"));
            if (rc != null) {
                c.review.relatedCode.enabled = asBool(rc.get("enabled"), c.review.relatedCode.enabled);
                c.review.relatedCode.maxFiles = asInt(rc.get("maxFiles"), c.review.relatedCode.maxFiles);
                c.review.relatedCode.maxFileChars = asInt(rc.get("maxFileChars"), c.review.relatedCode.maxFileChars);
                c.review.relatedCode.maxTotalChars = asInt(rc.get("maxTotalChars"), c.review.relatedCode.maxTotalChars);
            }
        }

        Map<String, Object> gr = asMap(m.get("guardrails"));
        if (gr != null) {
            c.guardrails.totalTimeBudgetMs = asInt(gr.get("totalTimeBudgetMs"), c.guardrails.totalTimeBudgetMs);
            c.guardrails.onTimeout = asStr(gr.get("onTimeout"), c.guardrails.onTimeout);
            Map<String, Object> ca = asMap(gr.get("cache"));
            if (ca != null) {
                c.guardrails.cache.enabled = asBool(ca.get("enabled"), c.guardrails.cache.enabled);
                c.guardrails.cache.ttlHours = asInt(ca.get("ttlHours"), c.guardrails.cache.ttlHours);
            }
        }

        Map<String, Object> pv = asMap(m.get("privacy"));
        if (pv != null) {
            c.privacy.redactSecrets = asBool(pv.get("redactSecrets"), c.privacy.redactSecrets);
            c.privacy.redactPatternsFile = asStr(pv.get("redactPatternsFile"), c.privacy.redactPatternsFile);
        }

        Map<String, Object> out = asMap(m.get("output"));
        if (out != null) {
            c.output.format = asStr(out.get("format"), c.output.format);
            c.output.color = asStr(out.get("color"), c.output.color);
            c.output.minSeverity = asStr(out.get("minSeverity"), c.output.minSeverity);
            c.output.showTokenUsage = asBool(out.get("showTokenUsage"), c.output.showTokenUsage);
        }

        Map<String, Object> tl = asMap(m.get("telemetry"));
        if (tl != null) {
            c.telemetry.enabled = asBool(tl.get("enabled"), c.telemetry.enabled);
            c.telemetry.endpoint = asStr(tl.get("endpoint"), c.telemetry.endpoint);
        }

        Object repos = m.get("repositories");
        if (repos instanceof List<?> list) {
            List<RepositoryConfig> parsed = new ArrayList<>();
            for (Object o : list) {
                Map<String, Object> rm = asMap(o);
                if (rm == null) continue;
                RepositoryConfig rc = new RepositoryConfig();
                rc.name = asStr(rm.get("name"), rc.name);
                rc.trunkBranch = asStr(rm.get("trunkBranch"), rc.trunkBranch);
                rc.agentsFile = asStr(rm.get("agentsFile"), rc.agentsFile);
                rc.bestPracticesFile = asStr(rm.get("bestPracticesFile"), rc.bestPracticesFile);
                rc.enabled = asBool(rm.get("enabled"), rc.enabled);
                Map<String, Object> mt = asMap(rm.get("match"));
                if (mt != null) {
                    rc.match.path = asStr(mt.get("path"), rc.match.path);
                    rc.match.remoteUrl = asStr(mt.get("remoteUrl"), rc.match.remoteUrl);
                }
                parsed.add(rc);
            }
            c.repositories = parsed;
        }
    }

    private static void applyEnvOverrides(Config c) {
        String baseUrl = System.getenv("AIREVIEW_LLM_BASEURL");
        if (baseUrl != null && !baseUrl.isBlank()) c.llm.baseUrl = baseUrl;
        String model = System.getenv("AIREVIEW_LLM_MODEL");
        if (model != null && !model.isBlank()) c.llm.model = model;
        String budget = System.getenv("AIREVIEW_GUARDRAILS_TOTALTIMEBUDGETMS");
        if (budget != null) c.guardrails.totalTimeBudgetMs = asInt(budget, c.guardrails.totalTimeBudgetMs);
        String maxDiff = System.getenv("AIREVIEW_REVIEW_MAXDIFFLINES");
        if (maxDiff != null) c.review.maxDiffLines = asInt(maxDiff, c.review.maxDiffLines);
    }

    /** Non-fatal validation: log problems; never throw (advisory guarantee). */
    private static void validate(Config c) {
        if (c.schemaVersion != 1) {
            Logs.warn("unsupported schemaVersion=" + c.schemaVersion + " (expected 1) — proceeding best-effort");
        }
        if (c.guardrails.totalTimeBudgetMs < c.llm.requestTimeoutMs) {
            Logs.warn("totalTimeBudgetMs < requestTimeoutMs; LLM call may not complete within budget");
        }
    }

    // ---- coercion helpers ----
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null; }
    static String asStr(Object o, String def) { return o == null ? def : String.valueOf(o); }
    static boolean asBool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o == null) return def;
        return Boolean.parseBoolean(String.valueOf(o));
    }
    static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    static double asDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? def : Double.parseDouble(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    @SuppressWarnings("unchecked")
    static List<String> asStrList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object e : l) out.add(String.valueOf(e));
            return out;
        }
        return null;
    }
}
