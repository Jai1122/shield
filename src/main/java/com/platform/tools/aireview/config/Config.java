package com.platform.tools.aireview.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Effective, merged configuration. Built by {@link ConfigLoader} from:
 * defaults &lt; global YAML &lt; repo YAML &lt; env vars.
 * Plain mutable POJO for simple YAML mapping; all fields have safe defaults.
 */
public final class Config {

    public int schemaVersion = 1;

    public final Llm llm = new Llm();
    public final Review review = new Review();
    public final Guardrails guardrails = new Guardrails();
    public final Privacy privacy = new Privacy();
    public final Output output = new Output();
    public final Telemetry telemetry = new Telemetry();
    public List<RepositoryConfig> repositories = new ArrayList<>();

    public static final class Llm {
        public String provider = "minimax";
        public String baseUrl = "https://myllm.com/minimax-m2/v1";
        public String chatPath = "/chat/completions";
        public String model = "/app/models/MiniMax-M2.5";
        public final Auth auth = new Auth();
        public double temperature = 0.1;
        public int maxOutputTokens = 1500;
        public int requestTimeoutMs = 18000;
        public int maxRetries = 1;
    }

    public static final class Auth {
        public String scheme = "bearer";   // bearer | basic | custom
        public String header = "Authorization";
        public boolean preEncoded = false;
    }

    public static final class Review {
        public String trunkBranch = "main";
        public List<String> reviewableIncludeGlobs = new ArrayList<>(List.of(
                "**/*.java", "**/*.kt", "**/*.groovy", "**/*.gradle",
                "**/*.sql", "**/*.yml", "**/*.yaml", "**/*.properties", "**/Dockerfile", "**/*.tf"));
        public List<String> reviewableExcludeGlobs = new ArrayList<>(List.of(
                "**/build/**", "**/generated/**", "**/*.lock", "**/node_modules/**"));
        public int maxRefsPerPush = 1;
        public int maxCommits = 50;
        public int maxChangedFiles = 60;
        public int maxDiffLines = 1500;
        public int diffContextLines = 3;
        public int maxRubricChars = 24000;
        public final SymbolGrep symbolGrep = new SymbolGrep();
        public final RelatedCode relatedCode = new RelatedCode();
    }

    public static final class SymbolGrep {
        public boolean enabled = true;
        public int maxGrepHits = 20;
    }

    /** L1 "related code": send full post-change bodies of changed files as review context. */
    public static final class RelatedCode {
        public boolean enabled = true;
        public int maxFiles = 20;          // cap number of bodies included
        public int maxFileChars = 16000;   // per-file cap; larger files degrade to signatures-only
        public int maxTotalChars = 60000;  // total budget across all bodies
    }

    public static final class Guardrails {
        public int totalTimeBudgetMs = 22000;
        public String onTimeout = "skip"; // skip | partial
        public final Cache cache = new Cache();
    }

    public static final class Cache {
        public boolean enabled = true;
        public int ttlHours = 168;
    }

    public static final class Privacy {
        public boolean redactSecrets = true;
        public String redactPatternsFile = null;
    }

    public static final class Output {
        public String format = "pretty"; // pretty | plain | json
        public String color = "auto";    // auto | always | never
        public String minSeverity = "info";
        public boolean showTokenUsage = false;
    }

    public static final class Telemetry {
        public boolean enabled = false;
        public String endpoint = null;
    }
}
