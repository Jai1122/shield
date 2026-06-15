# AI Pre-Push Code Review — MVP Specification

| Field | Value |
|---|---|
| Project | AI Pre-Push Code Review (working name: **aireview**) |
| Document | Engineering Specification — MVP (v1) |
| Status | Draft for review |
| Owner | Jayapal |
| Date | 2026-06-15 |
| Audience | Platform engineers, pilot-team developers |
| Target stack | Java 17+ Spring Boot services, Gradle (Groovy DSL) |
| LLM | MiniMax (configurable endpoint + model) |

> **One-line summary:** A client-side Git `pre-push` hook that, on every push, sends the
> pushed diff plus repo/best-practice context to MiniMax and prints an **advisory** code
> review to the developer's terminal. It **never blocks** a push.

---

## 0. How to read this document

This spec is the single source of truth for the MVP. It is intentionally detailed because
the framework will be installed on **many developers' machines across multiple repositories**;
ambiguity here becomes inconsistent behaviour in the field. Sections 1–4 are context and scope.
Sections 5–15 are the build contract. Sections 16–22 are operational concerns. Appendices
contain concrete, copy-ready artifacts.

Normative keywords **MUST**, **MUST NOT**, **SHOULD**, **MAY** are used in the RFC-2119 sense.

---

## 1. Background & motivation

The platform comprises **5 functional repositories** (Java Spring Boot services) and
**4 supporting repositories** (Helm charts, CI/CD pipelines). Each repository already contains
an `AGENTS.md` describing repo-/platform-specific knowledge. Deterministic quality gates —
**linting, tests, and security scans — are already in place** and are considered a *solved
problem*; this framework **MUST NOT** duplicate them.

The gap this framework fills is **judgment-level review**: design quality, intent matching,
logic/edge-case reasoning, clarity, and locally-visible duplication — the things a human
reviewer or a capable LLM provides that linters structurally cannot.

The team practises **trunk-based development** with frequent pushes, and wants review feedback
**at push time**.

### 1.1 Why this is an MVP (and what we are testing)

Industry consensus runs AI review **asynchronously at the PR/CI layer**, because synchronous
client-side LLM calls add latency to every push (the well-known "5-second rule": hooks slower
than ~5s get bypassed). We are deliberately choosing **client-side + synchronous + every-push**
for the MVP for one reason: **to validate whether the review output is valuable enough to
justify building the heavier server-side system.**

**The hypothesis under test:** *Developers find push-time, advisory, judgment-level review
useful enough to act on it, without the latency driving them to bypass it.*

If the MVP succeeds, the architecture migrates to **server-side `post-receive` → review-on-push**
with a repository index (see §21 Roadmap). The MVP is built so this migration reuses the prompt,
rubric, and LLM-client components.

---

## 2. Goals & non-goals

### 2.1 Goals (MVP)

- G1. On `git push`, produce an advisory review of exactly the commits being pushed.
- G2. Use each repo's `AGENTS.md` + a shared Java/Spring **best-practices** file as the rubric.
- G3. Incorporate developer **intent** (commit messages of the pushed range).
- G4. **Never** block, delay-fatally, or break a push under any failure condition.
- G5. Be installable on a Java developer's machine with **no new language runtime** (JVM only).
- G6. Be centrally maintainable: improving the reviewer **MUST NOT** require editing hooks on
  every machine/repo.
- G7. Keep latency within a budget that does not provoke mass bypassing (§17).

### 2.2 Non-goals (explicitly deferred)

- N1. **Repository-wide / cross-repo duplicate detection via an index or embeddings.**
  The MVP can only detect duplication *visible within the pushed diff* plus a cheap symbol-grep
  approximation (§11.4). True "this already exists elsewhere" detection is deferred to the
  server-side phase. *This limitation MUST be communicated to pilot users.*
- N2. Cross-repo contract / blast-radius analysis.
- N3. Asynchronous / background execution (MVP is synchronous by decision).
- N4. Server-side execution, persistent review storage, PR comment posting.
- N5. Blocking/gating on findings (MVP is advisory-only).
- N6. Re-implementing lint/test/security checks.
- N7. IDE integration.

---

## 3. Glossary

| Term | Meaning |
|---|---|
| **Hook** | The `pre-push` shell script Git invokes before transferring objects to a remote. |
| **CLI** | The fat JAR (`aireview.jar`) containing all review logic; invoked by the hook. |
| **Rubric** | Combined review instructions = shared best-practices file + repo `AGENTS.md`. |
| **Pushed range** | The set of commits being transferred to the remote for a given ref. |
| **Diff** | Unified diff of the pushed range. |
| **Intent** | Commit messages of the pushed range. |
| **Advisory** | Output is informational only; exit code is always success. |
| **Soft-fail** | On any internal error, log locally and exit 0 without affecting the push. |
| **Finding** | A single review observation (severity, file, line-ish, message, suggestion). |

---

## 4. Personas

- **P1 — Pilot developer.** Pushes Java/Spring code daily. Wants useful feedback, hates slow
  pushes. Will bypass a tool that annoys them. Primary UX constraint.
- **P2 — Platform engineer (maintainer).** Owns the framework, the best-practices file, the
  prompt, and releases of the JAR. Needs central control and telemetry on whether it's working.
- **P3 — Tech lead.** Wants evidence (metrics) that the MVP improves review quality before
  funding the server-side build.

---

## 5. High-level architecture

```
 developer: git push
        │
        ▼
 ┌─────────────────────────┐   stdin: <localRef> <localSHA> <remoteRef> <remoteSHA>  (one line/ref)
 │  pre-push hook (sh)      │   argv: <remoteName> <remoteURL>
 │  (thin, version-pinned)  │
 └───────────┬─────────────┘
             │ exec, pass stdin through, pass remote args
             ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  aireview.jar  (Java, fat JAR)                                │
 │                                                              │
 │  1. Parse pushed refs (skip deletes; resolve new-branch range)│
 │  2. Compute diff + intent (git plumbing)                      │
 │  3. Load rubric: best-practices.md + ./AGENTS.md             │
 │  4. (opt) Symbol-grep duplicate approximation                │
 │  5. Guardrails: size cap, cache lookup                        │
 │  6. Build prompt → call MiniMax (timeout, retry)             │
 │  7. Parse structured findings                                 │
 │  8. Render to terminal                                        │
 │  9. Cache result                                             │
 └───────────┬─────────────────────────────────────────────────┘
             │ ALWAYS exit 0
             ▼
   push proceeds (Git transfers objects)
```

**Design invariants**
- The hook is **dumb**; all logic lives in the CLI (satisfies G6 — upgrade JAR, not hooks).
- The CLI's process exit code is **decoupled** from review content (satisfies G4 — advisory).
- Every external/uncertain operation is wrapped so failure → soft-fail → exit 0.

---

## 5.1 Entry points

The CLI exposes two review entry points that share one core pipeline
(`ReviewService.reviewRange`) and one runner (`Main.execute`, which handles config load,
credential check, time-budget watchdog, and rendering):

| Subcommand | Trigger | Range reviewed | Intent source | Notes |
|---|---|---|---|---|
| `pre-push <remote> <url>` | git hook on push (reads stdin) | per pushed ref (§7.2) | commit messages of the range | the primary MVP flow |
| `commit <id> [--jira "…" \| --jira-file <p>]` | manual / CI | `parent(id)..id` | **Jira/ticket description** + the commit's own message | Jira text may also be piped via stdin |

Both are **advisory** and exit 0 in all cases (§9). The `commit` entry point exists so a reviewer
can be run outside the push flow — e.g. in CI against a specific SHA, or on demand for a ticket —
with the Jira description supplying the intent the model checks the change against. A root commit
(no parent) is skipped with a message. The Jira text participates in the cache key, so re-running
with a different description re-reviews. (`review` = dry-run of `HEAD~1..HEAD`; `doctor` =
self-check.)

## 6. Component inventory

| # | Component | Artifact | Owner | Distribution |
|---|---|---|---|---|
| C1 | Pre-push hook | `pre-push` (POSIX sh) | Platform | Tracked in repo under `.githooks/` |
| C2 | Review CLI | `aireview-<version>.jar` | Platform | Internal Maven repo (Nexus/Artifactory) |
| C3 | Launcher wrapper | `aireview` (sh) | Platform | `~/.local/bin` via installer |
| C4 | Global config | `~/.config/aireview/config.yml` | Developer | Created by installer |
| C5 | Secrets | `~/.config/aireview/.env` | Developer | Created by installer (chmod 600) |
| C6 | Best-practices rubric | `best-practices-java-spring.md` | Platform | Bundled in JAR + overridable |
| C7 | Per-repo knowledge | `AGENTS.md` | Repo team | Already present in each repo |
| C8 | Installer | `install.sh` | Platform | Run once per machine |
| C9 | Cache | `~/.cache/aireview/` | (runtime) | Created on first run |
| C10 | Local log | `~/.cache/aireview/aireview.log` | (runtime) | Rotating |

---

## 7. Detailed execution flow

### 7.1 Hook invocation contract (Git-defined — MUST be honoured exactly)

Git calls `pre-push` with:

- **argv[1]** = remote name (e.g. `origin`)
- **argv[2]** = remote URL
- **stdin** = zero or more lines, one per ref being pushed:
  ```
  <local ref> SP <local oid> SP <remote ref> SP <remote oid> LF
  ```

The hook **MUST** forward argv and stdin verbatim to the CLI. The CLI reads stdin once.

### 7.2 Ref parsing rules (MUST)

For each stdin line:

1. **Branch deletion** — `local oid` is all-zeros (`0000000000…`) → **skip** (nothing to review).
2. **New branch/ref on remote** — `remote oid` is all-zeros → there is no remote ancestor.
   Compute the review range as commits not present on any remote:
   `git rev-list <local oid> --not --remotes` (capped, see §7.4). Diff base = merge-base of
   `<local oid>` and the configured trunk (`origin/main` by default) if available, else the
   parent of the oldest new commit.
3. **Normal update** — review range = `<remote oid>..<local oid>`.
4. **Force push (non-fast-forward)** — treat as normal update over `<remote oid>..<local oid>`;
   if `remote oid` is not an ancestor of `local oid`, fall back to reviewing commits via
   `git rev-list <local oid> --not <remote oid>` and note "history rewritten" in the report.
5. **Tags** — refs under `refs/tags/` → skip in MVP (configurable).

If **multiple refs** are pushed, the CLI reviews each in-scope ref. To protect latency, the MVP
reviews at most `maxRefsPerPush` (default 1, the first branch ref); additional refs are noted as
"not reviewed (MVP limit)". Rationale: trunk-based pushes are normally single-branch.

### 7.3 Diff & intent extraction (MUST)

- **Diff:** `git diff --no-color --unified=<context> <base>..<tip>` filtered to reviewable
  paths (§7.5). Binary files excluded.
- **Intent:** `git log --no-color --format='%H%n%s%n%b%n---' <range>`.
- **Changed file list + churn:** `git diff --numstat <base>..<tip>` for size guardrails and
  the symbol-grep step.

All git invocations run with explicit `--no-color`, no pager (`GIT_PAGER=cat`), and from the
repository root resolved via `git rev-parse --show-toplevel`.

### 7.4 Commit/range caps (MUST)

To bound work: if the range exceeds `maxCommits` (default 50) or `maxChangedFiles`
(default 60) or `maxDiffLines` (default 1500), apply the size-cap behaviour (§10.2).

### 7.5 Reviewable path filter (SHOULD)

Default include globs: `**/*.java`, `**/*.kt`, `**/*.groovy`, `**/*.gradle`, `**/*.sql`,
`**/*.yml`, `**/*.yaml`, `**/*.properties`, `Dockerfile`, `**/*.tf`. Default exclude:
`**/build/**`, `**/generated/**`, `**/*.lock`, `**/node_modules/**`, vendored dirs.
Overridable in config.

### 7.6 Rubric assembly (MUST)

First, **resolve the current repository** against `repositories[]` (§8.2): match by worktree path
(`git rev-parse --show-toplevel`) or `origin` URL. A match supplies per-repo `trunkBranch`,
`agentsFile`, and `bestPracticesFile`; **no match → use global defaults and continue** (the tool
still runs in any repo). A matched entry with `enabled: false` → skip review silently (exit 0).

Rubric = (a) best-practices file (resolution order: matched repo's `bestPracticesFile` → repo-local
`.aireview/best-practices.md` → global config path → JAR-bundled default) **+** (b) the repo's
`agentsFile` (default `AGENTS.md`) if present. Each is truncated to `maxRubricChars`
(default 24000 chars each) with a truncation marker.

### 7.7 Symbol-grep duplicate approximation (MAY — feature-flagged, default ON)

1. Extract identifiers introduced by the diff: new `class`/`record`/`interface`/`enum` names and
   new method signatures (regex over added lines).
2. For each identifier, `git grep -n -w <identifier>` across the repo (excluding the changed
   files), cap to `maxGrepHits` (default 20) total.
3. Provide hits to the model as "possibly-related existing code" context.

This is a **heuristic**, not an index; it is explicitly a degraded stand-in for N1.

### 7.8 LLM call (MUST)

Build prompt (§11), call MiniMax (§12) under the global time budget (§10.1). Parse the
structured JSON response into `Finding[]`.

### 7.9 Render & cache (MUST)

Render findings to the terminal (§13), then write the result to cache keyed per §10.4. Always
`exit 0` (§14).

---

## 8. Configuration specification

### 8.1 Files & precedence

Effective config = defaults (in JAR) ◁ global `~/.config/aireview/config.yml` ◁ repo
`.aireview/config.yml` ◁ environment variables ◁ CLI flags. (Right-most wins.)

Secrets live **only** in `~/.config/aireview/.env` (or the real environment), never in YAML and
never in a repo.

### 8.2 `config.yml` schema (v1)

```yaml
schemaVersion: 1            # int; CLI refuses unknown major versions (soft-fail + warn)

llm:
  provider: minimax                                  # self-hosted, OpenAI-compatible (vLLM)
  baseUrl: "https://myllm.com/minimax-m2/v1"         # config-driven; chatPath is appended
  chatPath: "/chat/completions"                       # OpenAI-compatible route
  model: "/app/models/MiniMax-M2.5"                   # server-side model path/name (config-driven)
  auth:
    scheme: "bearer"         # bearer | basic | custom   (config-driven; sample uses Bearer)
    header: "Authorization"  # header name to set
    # For scheme=basic: header value = "Basic " + base64(<user>:<password>)
    # For scheme=bearer: header value = "Bearer " + <token>
    # For scheme=custom: header value = <token> verbatim
    # Credentials themselves live ONLY in .env (never in YAML) — see §8.3.
    preEncoded: false        # if true, .env already holds the base64 string; CLI sends as-is
  temperature: 0.1
  maxOutputTokens: 1500
  requestTimeoutMs: 18000
  maxRetries: 1

# Managed repositories. Array now (single entry for the pilot) so the same config
# scales to all 9 repos later without a schema change. The CLI resolves the CURRENT
# repo (via `git rev-parse --show-toplevel` and/or remote URL) and matches it against
# this list to apply per-repo overrides. No match → use global defaults (still runs).
repositories:
  - name: "order-service"          # pilot repo
    match:
      path: "/abs/path/to/order-service"   # absolute worktree path, OR
      remoteUrl: null                       # match by `origin` URL instead (optional)
    trunkBranch: "main"            # overrides review.trunkBranch for this repo
    agentsFile: "AGENTS.md"        # repo-relative; default AGENTS.md
    bestPracticesFile: null        # repo-relative override; null → global/bundled
    enabled: true

review:
  trunkBranch: "main"
  reviewableIncludeGlobs: ["**/*.java", "**/*.gradle", "**/*.yml", "**/*.sql", "Dockerfile"]
  reviewableExcludeGlobs: ["**/build/**", "**/generated/**"]
  maxRefsPerPush: 1
  maxCommits: 50
  maxChangedFiles: 60
  maxDiffLines: 1500
  diffContextLines: 3
  maxRubricChars: 24000
  symbolGrep:
    enabled: true
    maxGrepHits: 20

guardrails:
  totalTimeBudgetMs: 22000   # hard wall-clock ceiling for the whole CLI
  onTimeout: "skip"          # skip | partial
  cache:
    enabled: true
    ttlHours: 168            # 7 days

privacy:
  redactSecrets: true        # scrub secret-looking strings from diff before sending (§16.2)
  redactPatternsFile: null   # optional path to extra regexes

output:
  format: "pretty"           # pretty | plain | json
  color: "auto"              # auto | always | never
  minSeverity: "info"        # info | minor | major | critical
  showTokenUsage: false

telemetry:
  enabled: false             # opt-in; see §19
  endpoint: null
```

### 8.3 `.env` schema

```
# Credentials for the configured llm.auth.scheme — choose the set that applies:

# scheme=basic  → CLI builds "Basic " + base64(USER:PASSWORD) at send time
AIREVIEW_API_USER=<username>
AIREVIEW_API_PASSWORD=<password>

# scheme=basic with preEncoded=true → provide the base64 string directly
AIREVIEW_API_BASIC=<base64(user:password)>

# scheme=bearer / custom
AIREVIEW_API_TOKEN=<token>

# optional overrides (non-secret config may also be set here or in config.yml)
AIREVIEW_LLM_BASEURL=...
AIREVIEW_LLM_MODEL=...
```

Credential resolution: the CLI picks the variable matching `llm.auth.scheme`. Missing the
required credential → soft-fail (§10.3). The base64 encoding for Basic auth is performed by the
CLI unless `preEncoded: true`.

### 8.4 Environment variable overrides (MUST)

Every scalar config key has an env override: `AIREVIEW_<UPPER_SNAKE_PATH>`
(e.g. `AIREVIEW_REVIEW_MAXDIFFLINES=2000`). Used for per-machine tuning and CI testing.

### 8.5 Config validation (MUST)

On startup the CLI validates the merged config. Invalid config → **soft-fail**: log the error,
print a one-line warning, `exit 0`. A broken config **MUST NOT** block pushing.

---

## 9. Exit-code & advisory guarantee (the most important contract)

- The CLI process **MUST `exit 0` in all cases** in the MVP — success, findings present, LLM
  error, timeout, bad config, missing key, even unhandled exception (top-level catch-all).
- The hook **MUST** propagate `exit 0` (and itself `exit 0` even if the CLI is missing).
- Consequence: the review is purely advisory; the push outcome depends only on Git.
- *Forward-compat:* a future `guardrails.blockOnSeverity` setting is **out of scope** for MVP and
  MUST default to disabled when introduced.

A dedicated test (§20) asserts that every failure mode yields exit 0.

---

## 10. Safeguards (latency & robustness)

These four safeguards determine whether developers keep the tool or bypass it.

### 10.1 Hard time budget

- A single wall-clock budget `guardrails.totalTimeBudgetMs` (default 22s) governs the **entire**
  CLI run, enforced by a watchdog timer on a separate thread.
- The LLM HTTP call additionally has `llm.requestTimeoutMs` (default 18s).
- On budget breach: stop work, print `⏱ aireview: review skipped (time budget exceeded)`,
  exit 0. (`onTimeout: partial` MAY render any findings already received from a streamed
  response; default `skip`.)
- **Note for users:** synchronous design means the developer waits up to the budget on each push.
  This is the central UX risk and the primary metric to watch (§19).

### 10.2 Diff-size cap

- If the range exceeds `maxCommits` / `maxChangedFiles` / `maxDiffLines`, the CLI **MUST NOT**
  send the full diff. MVP behaviour: print
  `aireview: change too large for push-time review (N files / M lines) — skipped` and exit 0.
- Rationale: bounds latency and token cost; trunk-based pushes are normally small so this rarely
  fires.

### 10.3 Soft-fail on missing/invalid credentials

- No `AIREVIEW_API_KEY` → print one-line hint (`aireview: no API key configured — skipping
  review (run: aireview doctor)`) and exit 0.
- Auth error from MiniMax (401/403) → same treatment.
- A misconfigured laptop **MUST** never block a push.

### 10.4 Caching + progress

- **Cache key** = SHA-256 of `(normalized diff + intent + rubric content + model id + prompt
  template version)`. Identical re-push → instant cache hit, no LLM call.
- Cache stored at `~/.cache/aireview/<key>.json`, TTL `cache.ttlHours`.
- **Progress:** while waiting, show a spinner + elapsed seconds on stderr so the push never looks
  hung. Suppressed when stdout is not a TTY or `output.color=never`.

---

## 11. Prompt design

### 11.1 Structure

A single chat completion with a **system** message (rubric + rules + output contract) and a
**user** message (the change). Prompt template carries a `promptTemplateVersion` (string) that
participates in the cache key and telemetry.

### 11.2 System message (assembled)

```
You are a senior Java / Spring Boot reviewer performing an ADVISORY push-time review.

CONTEXT — repository knowledge (AGENTS.md):
<AGENTS.md, truncated>

CONTEXT — engineering best practices:
<best-practices-java-spring.md, truncated>

SCOPE — what NOT to do (handled by other tooling):
- Do NOT report formatting/style, lint, compilation, test presence, or secret/security-scan
  findings. Linting, tests, and security scanning are already enforced.
- Do NOT restate the diff or summarize what the code does unless it reveals a problem.

FOCUS — review for:
1. Design & abstraction quality; Spring idioms (layering, DI, transactions, bean scope).
2. Logic correctness & edge cases (null handling, Optional misuse, concurrency, JPA N+1 /
   lazy-loading, transaction boundaries / self-invocation).
3. Intent match: does the change accomplish what the commit messages claim?
4. Duplication VISIBLE in this diff or in the provided "possibly-related existing code".
5. Clarity & maintainability of the changed code only.

RULES:
- Review ONLY the changed lines and their immediate context. Do not invent code you cannot see.
- Prefer few high-confidence findings over many speculative ones (false positives erode trust).
- If you have no material findings, return an empty findings array.

OUTPUT — respond with ONLY valid JSON matching this schema:
{ "summary": string,
  "findings": [ { "severity": "critical|major|minor|info",
                  "file": string, "line": integer|null,
                  "title": string, "detail": string,
                  "suggestion": string|null } ] }
```

### 11.3 User message

```
COMMIT MESSAGES (intent):
<intent>

POSSIBLY-RELATED EXISTING CODE (heuristic, may be irrelevant):
<symbol-grep hits or "none">

DIFF (unified):
<filtered, redacted diff>
```

### 11.4 Notes

- The duplication focus is bounded to *visible* context — consistent with N1. The prompt MUST NOT
  imply repo-wide duplicate detection.
- The "few high-confidence findings" instruction is deliberate: benchmark data shows false
  positives are the primary reason teams abandon AI reviewers.

---

## 12. LLM integration (MiniMax)

### 12.1 Transport

- HTTPS POST chat-completion to the **self-hosted** `llm.baseUrl`. Endpoint path, request envelope,
  and model name are **config-driven** (§8.2). The exact request/response shape of the self-hosted
  MiniMax deployment MUST be confirmed against its API before implementation — do not assume
  OpenAI-identical shapes. Implement behind a small `LlmClient` interface so the concrete mapping
  is isolated (and so the server-side phase can reuse it).
- **Auth (config-driven, §8.2 `llm.auth`):**
  - `basic` → `Authorization: Basic <base64(user:password)>` — CLI base64-encodes from
    `AIREVIEW_API_USER`/`AIREVIEW_API_PASSWORD` (or sends `AIREVIEW_API_BASIC` verbatim if
    `preEncoded: true`).
  - `bearer` → `Authorization: Bearer <AIREVIEW_API_TOKEN>`.
  - `custom` → set `llm.auth.header` to the token value verbatim.
  - Header name overridable via `llm.auth.header`.
- Since the endpoint is **self-hosted on the internal network**, plain-`http` base URLs MAY be
  permitted (config), but TLS is RECOMMENDED. The CLI MUST NOT log the encoded credential.
- Timeouts: connect + read per `llm.requestTimeoutMs`. Use the JDK `java.net.http.HttpClient`
  (no heavy deps).

### 12.2 Parameters

- `temperature` default 0.1 (deterministic-ish review).
- `maxOutputTokens` default 1500.
- Request JSON output mode if MiniMax supports it; otherwise instruct JSON-only in the prompt and
  parse defensively (§12.4).

### 12.3 Retry policy

- `maxRetries` default 1, only on transient errors (timeout, 429, 5xx), with jittered backoff,
  and only if within the total time budget. Never retry on 4xx auth/validation.

### 12.4 Response parsing (defensive — MUST)

- Strip code fences / leading prose; extract the first balanced JSON object.
- Validate against the schema; coerce/skip malformed findings.
- Unparseable response → treat as "no usable review": print a one-line notice, exit 0.

### 12.5 Token budgeting

- Estimate prompt tokens (~chars/4) before sending. If estimate exceeds the model context minus
  output budget, apply size-cap behaviour (§10.2) rather than silent truncation.
- The response `usage` object (`prompt_tokens`, `completion_tokens`, `total_tokens`) MUST be
  captured for telemetry/logging (§18/§19) and optionally shown when `output.showTokenUsage`.

### 12.6 Concrete wire format (self-hosted vLLM, OpenAI-compatible) — CONFIRMED

Verified against the deployment's sample. The `LlmClient` MUST implement exactly this.

**Request** — `POST {baseUrl}{chatPath}` (e.g. `https://myllm.com/minimax-m2/v1/chat/completions`):

```
Headers:
  Authorization: Bearer <AIREVIEW_API_TOKEN>      # per llm.auth.scheme
  Content-Type: application/json

Body:
{
  "model": "<llm.model>",                          # e.g. "/app/models/MiniMax-M2.5"
  "messages": [
    { "role": "system", "content": "<assembled system message §11.2>" },
    { "role": "user",   "content": "<user message §11.3>" }
  ],
  "temperature": <llm.temperature>,                # 0.1
  "max_tokens": <llm.maxOutputTokens>,             # 1500  (OpenAI/vLLM name)
  "stream": false
}
```

- **JSON output:** attempt `"response_format": {"type": "json_object"}` (vLLM supports guided/JSON
  decoding on many builds). If the server rejects it (400), retry once **without** the field and
  fall back to prompt-instructed JSON + defensive parsing (§12.4). Detect support once and cache
  the result for the process.

**Response** — extract `choices[0].message.content` (the JSON review payload), then parse per
§12.4:

```json
{
  "id": "chatcmpl-…",
  "model": "hosted_vllm//app/models/MiniMax-M2.5",
  "object": "chat.completion",
  "choices": [
    { "index": 0, "finish_reason": "stop",
      "message": { "role": "assistant", "content": "<our JSON review payload as a string>" } }
  ],
  "usage": { "prompt_tokens": 43, "completion_tokens": 940, "total_tokens": 983 }
}
```

- **`finish_reason` handling:** `stop` → normal. `length` → output was truncated; parse what's
  valid and append a note "review truncated (raise maxOutputTokens)". Anything else → treat as
  no usable review (§12.4).
- Note the response `model` is prefixed `hosted_vllm/…`; ignore — informational only.

---

## 13. Output / terminal UX

### 13.1 Pretty format (default)

```
─ aireview ─ advisory review (range abc123..def456, 3 files, 87 lines) ─ 6.2s ─

Summary: Solid change. Two concerns around transaction scope and a possible N+1.

  ● MAJOR  OrderService.java:142  Transaction may not commit as intended
     placeOrder() calls a @Transactional method via 'this' — self-invocation bypasses
     the proxy, so the inner transaction settings are ignored.
     ↳ Suggestion: move the inner method to a separate bean, or restructure boundaries.

  ● MINOR  OrderRepository.java:30  Possible N+1 on order.items
     findAllByStatus iterates lazy 'items'. Consider a fetch join or @EntityGraph.

  ● INFO   OrderMapper.java:12  Possible duplication
     'toDto' resembles existing OrderDtoFactory.build (heuristic match) — verify.

3 findings (0 critical, 1 major, 1 minor, 1 info).  Advisory only — push proceeds.
```

### 13.2 Rules

- Sort by severity (critical→info), then file.
- Respect `output.minSeverity`.
- Empty findings → single green line: `aireview: no material findings. ✔  (4.1s)`.
- All review output goes to **stderr** so it never pollutes any stdout consumers; the spinner is
  stderr too. (The push itself is unaffected either way.)
- `format: json` prints the raw structured result (for tooling/tests). `format: plain` is
  no-color pretty.

---

## 14. Failure-mode matrix (MUST all yield exit 0)

| Condition | Behaviour | Exit |
|---|---|---|
| JAR missing / launcher not found | Hook prints hint, continues | 0 |
| No API key | One-line hint, skip | 0 |
| Auth error (401/403) | One-line hint, skip | 0 |
| Network error / DNS | One-line notice, skip | 0 |
| LLM timeout / budget exceeded | "review skipped (timeout)" | 0 |
| 429 / 5xx after retries | One-line notice, skip | 0 |
| Unparseable LLM response | "no usable review", skip | 0 |
| Diff too large | "change too large … skipped" | 0 |
| Not a git repo / no commits in range | Silent skip | 0 |
| Branch deletion / tags only | Silent skip | 0 |
| Invalid config | Warn, skip | 0 |
| Unhandled exception | Catch-all logs to file, prints generic notice | 0 |

---

## 15. Distribution, installation & versioning

### 15.1 Build (Gradle, Groovy DSL)

- Single module producing a **fat/shadow JAR** (`com.github.johnrengelman.shadow` or Gradle
  `application` + manifest). Output: `aireview-<version>-all.jar`.
- Java 17 toolchain (matches Spring Boot 3 services).
- Reproducible version from a `version` property; `--version` flag prints it.

### 15.2 Artifact hosting

- Publish the fat JAR to the **internal Maven repository** (Nexus/Artifactory) the team already
  uses for Spring artifacts. Versions are immutable and pinned.

### 15.3 Per-machine install (`install.sh`)

1. Verify JVM ≥ 17 (`java -version`); fail with guidance if absent.
2. Download pinned `aireview-<version>-all.jar` from the internal repo into
   `~/.local/share/aireview/`.
3. Install launcher `aireview` → `~/.local/bin/aireview` (wraps `java -jar …`).
4. Scaffold `~/.config/aireview/config.yml` and `.env` (chmod 600) if absent.
5. `aireview doctor` self-check (JVM, key present, config valid, MiniMax reachable).

### 15.4 Per-repo hook install (pilot)

- The hook lives **tracked** in the repo at `.githooks/pre-push`.
- Activation per clone: `git config core.hooksPath .githooks` (run by a repo `setup` task or
  documented one-liner). Chosen over copying into `.git/hooks` so the hook is versioned with the
  repo and updated via normal pulls.
- *Considered & deferred:* Lefthook/pre-commit managers — add a dependency without MVP benefit
  for a single pilot repo. Revisit when rolling to all 9 repos.

### 15.5 Versioning & compatibility

- **Semantic versioning** of the JAR.
- `schemaVersion` (config) and `promptTemplateVersion` (prompt) are independent and logged.
- The hook is pinned to a **minimum JAR version**; on mismatch it warns (still exits 0).
- Upgrades: bump pinned version in internal repo + rerun `install.sh` (or a self-update command).
  Hooks rarely change (G6).

### 15.6 Uninstall

`install.sh --uninstall` removes launcher, JAR, and (with `--purge`) config/cache. Repo hook
deactivated via `git config --unset core.hooksPath`.

---

## 16. Security & privacy

### 16.1 Credentials

- API key stored in `~/.config/aireview/.env`, `chmod 600`, never logged, never in YAML, never in
  a repo. Logs redact any `AIREVIEW_API_KEY` value.

### 16.2 Data egress (IMPORTANT)

- MiniMax is **self-hosted on the internal network**, so the diff and commit messages stay inside
  the organisation's perimeter — there is **no third-party egress**. This materially lowers the
  data-policy risk versus an external SaaS LLM.
- The diff is still sent over the network to the MiniMax host; therefore **TLS is RECOMMENDED**
  and the destination host MUST be the trusted internal deployment (validate `baseUrl`).
- **Secret redaction before send** (`privacy.redactSecrets: true`, default ON) is retained as
  defence-in-depth: scrub high-confidence secret patterns (AWS keys, private-key blocks, bearer
  tokens, `password=`, JDBC URLs with credentials, common token formats) from the diff prior to
  the call. Not a replacement for the existing secret scanner.
- Confirm the self-hosted MiniMax deployment's **logging/retention** of prompts so reviewed diffs
  are not inadvertently persisted in plaintext server logs; document in the rollout doc.

### 16.3 Supply chain

- Pin all CLI dependencies; prefer JDK built-ins (`HttpClient`, no external HTTP lib if avoidable)
  to minimise surface. Generate a dependency report at build.

### 16.4 Hook trust

- `core.hooksPath` points at a repo-tracked dir; reviewers of the repo can audit the hook. The
  hook only execs the pinned launcher; it contains no secrets and no network calls itself.

---

## 17. Performance targets

| Metric | Target | Hard ceiling |
|---|---|---|
| Added push latency (cache miss, typical small diff) | ≤ 8 s p50 | `totalTimeBudgetMs` (22 s) |
| Added push latency (cache hit) | < 300 ms | — |
| CLI cold JVM start | ≤ 600 ms | — |
| Diff/intent/git plumbing | ≤ 500 ms | — |

If p50 added latency trends above ~8s in the pilot, that is a **stop-and-reassess** signal for the
synchronous design (see §19/§21).

---

## 18. Observability & logging

- **Local log:** `~/.cache/aireview/aireview.log`, rotating (size-based, e.g. 5×1MB). Records
  timestamp, range, file/line counts, latency breakdown, cache hit/miss, outcome, error class.
  **Never** logs diff content or secrets by default (`--debug` may log more, with a warning).
- `aireview doctor` — environment self-diagnosis.
- `aireview review --dry-run` — run against the last commit range without pushing (for tuning).

---

## 19. Success metrics (how we judge the MVP)

The MVP exists to test the hypothesis in §1.1. Track (opt-in telemetry, §8.2; otherwise via a
short pilot survey + local logs):

- **M1 Adoption/retention:** % of pushes where the hook ran vs. bypassed (`--no-verify`).
  *Primary signal — high bypass = latency lost.*
- **M2 Latency:** p50/p95 added push time.
- **M3 Usefulness:** developer-rated usefulness; % of findings acted upon.
- **M4 Signal quality:** false-positive rate (findings marked "wrong/irrelevant").
- **M5 Volume:** findings per push by severity.

**Go/no-go to server-side:** retention high (low bypass), p95 latency tolerable, usefulness
positive, false-positive rate low. Otherwise iterate on prompt/rubric or reconsider sync design.

---

## 20. Testing strategy

- **Unit:** ref-line parsing (delete/new-branch/force/multi-ref/tags), diff range computation,
  config merge/precedence, env overrides, redaction regexes, JSON extraction from messy LLM
  output, cache key stability, size-cap logic.
- **Contract:** `LlmClient` against a mock MiniMax server (success, 401, 429, 5xx, timeout,
  malformed JSON, fenced JSON).
- **Failure-mode suite:** assert **exit 0** for every row in §14.
- **Integration:** temp git repo fixtures driving a real `git push` to a local bare remote with a
  stubbed LLM; assert push always succeeds and output renders.
- **Golden prompts:** snapshot the assembled prompt for representative diffs to catch accidental
  prompt drift (tied to `promptTemplateVersion`).
- **Performance smoke:** assert budget/timeout enforcement.

---

## 21. Roadmap (post-MVP) — migration to server-side

If §19 go-criteria are met:

1. Move execution to **server-side `post-receive` → review-on-push** (async, central, no laptop
   latency, can't be bypassed).
2. Add the **repository index** (semantic/graph) enabling **real duplicate detection (N1)** and
   **cross-repo contract analysis (N2)**.
3. Persistent delivery (Slack/Teams or in-Git comments) instead of terminal.
4. Reuse from MVP: `LlmClient`, prompt/rubric, finding schema, redaction, config model.

Build-vs-buy checkpoint: evaluate self-hosted **PR-Agent** (own LLM/MiniMax) and **Greptile
self-hosted** (graph/duplicate detection) before building the index in-house.

---

## 22. Open questions & risks

| ID | Item | Type | Disposition |
|---|---|---|---|
| Q1 | Self-hosted MiniMax request/response envelope | **Resolved** | OpenAI-compatible (vLLM); exact shape documented in §12.6 |
| Q2 | Deployment model | **Resolved** | Self-hosted on internal network (no third-party egress) |
| Q3 | Self-hosted MiniMax prompt logging/retention | Risk | Confirm server doesn't persist diffs in plaintext logs (§16.2) |
| R1 | Synchronous latency drives bypassing | Risk | Mitigated by §10; watched by M1/M2 |
| R2 | False positives erode trust | Risk | Mitigated by prompt §11.4; watched by M4 |
| R3 | Degraded duplicate detection misleads users | Risk | Disclose N1 clearly to pilot |
| R4 | Client-hook drift across machines | Risk | `core.hooksPath` + pinned JAR; logic in CLI |
| Q4 | Pilot repo identity (name/path/remote for `repositories[0]`) | Open | Fill in `repositories[]` entry (§8.2) |

---

## Appendix A — `pre-push` hook (reference)

```sh
#!/bin/sh
# .githooks/pre-push  — thin, advisory. NEVER blocks a push.
# Git passes: argv: <remoteName> <remoteURL>; stdin: <localRef> <localSHA> <remoteRef> <remoteSHA>
set -u

LAUNCHER="${AIREVIEW_BIN:-$HOME/.local/bin/aireview}"

if [ ! -x "$LAUNCHER" ]; then
  echo "aireview: not installed — skipping review (see install docs)" >&2
  exit 0
fi

# Forward remote args + stdin verbatim. The CLI is responsible for ALL guardrails and
# MUST itself exit 0; we hard-guarantee exit 0 here regardless.
"$LAUNCHER" pre-push "$@" || true
exit 0
```

## Appendix B — `aireview` launcher (reference)

```sh
#!/bin/sh
# ~/.local/bin/aireview
set -u
JAR="${AIREVIEW_JAR:-$HOME/.local/share/aireview/aireview.jar}"
# load secrets if present
if [ -f "$HOME/.config/aireview/.env" ]; then
  set -a; . "$HOME/.config/aireview/.env"; set +a
fi
exec java -jar "$JAR" "$@"
```

## Appendix C — Gradle (Groovy) skeleton

```groovy
plugins {
    id 'java'
    id 'application'
    id 'com.github.johnrengelman.shadow' version '8.+'
}

group = 'com.platform.tools'
version = '0.1.0'

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

application {
    mainClass = 'com.platform.tools.aireview.Main'
}

repositories { mavenCentral() }   // + internal Nexus/Artifactory for publish

dependencies {
    // Prefer JDK built-ins (java.net.http). Minimal deps only:
    implementation 'org.yaml:snakeyaml:2.+'        // config
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.+' // JSON parse
    testImplementation platform('org.junit:junit-bom:5.+')
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

shadowJar {
    archiveBaseName = 'aireview'
    archiveClassifier = 'all'
    archiveVersion = project.version
}

test { useJUnitPlatform() }
```

## Appendix D — directory layout

```
~/.config/aireview/         config.yml, .env (600)
~/.local/share/aireview/    aireview.jar
~/.local/bin/aireview       launcher
~/.cache/aireview/          <key>.json caches, aireview.log

<repo>/.githooks/pre-push   tracked hook (core.hooksPath)
<repo>/AGENTS.md            repo knowledge (existing)
<repo>/.aireview/           optional repo overrides: config.yml, best-practices.md
```

---

*End of MVP specification v1.*
