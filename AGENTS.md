# AGENTS.md — aireview

Context for AI coding agents (and humans) working in this repo. This project is itself an
advisory AI code-review tool, so changes here should hold to the same bar it enforces.

## What this is

`aireview` is a client-side Git `pre-push` hook + a Java CLI (fat JAR). On `git push` it sends the
pushed diff plus repo context to a **self-hosted, OpenAI-compatible (vLLM) MiniMax** endpoint and
prints an **advisory** review. Full design is in [`SPEC.md`](SPEC.md). This is an MVP; the roadmap
to a server-side version is in SPEC §21.

## Golden invariant (do not violate)

**The CLI must `exit 0` in every case** — success, findings, timeout, missing credentials, network
error, bad config, even an unhandled exception. A reviewer must never block a push. Any new code
path that can throw must be wrapped so it degrades to a soft-fail (log + skip + exit 0). See
`Main.dispatch` (top-level catch-all), the failure-mode matrix in SPEC §14, and `PushRefTest` /
parser tests. If you add a failure path, add a test asserting exit 0 / graceful skip.

## Build & test

```bash
gradle build            # compile + test + fat JAR  (build/libs/aireview-<version>-all.jar)
gradle test             # tests only
gradle shadowJar        # fat JAR only
```

- JDK **17** (toolchain pinned). Gradle **9** with the `com.gradleup.shadow` plugin (the old
  `com.github.johnrengelman.shadow` + `application` plugin combo breaks on Gradle 9 via the removed
  `mainClassName`; do not reintroduce the `application` plugin).
- Keep dependencies minimal: only SnakeYAML (config) and Jackson (JSON). **HTTP uses the JDK
  `java.net.http.HttpClient`** — do not add an HTTP library.

## Entry points (subcommands)

- `pre-push` — invoked by the git hook; reads ref lines from stdin (push-time review).
- `commit <id> [--jira "text" | --jira-file <path>]` — manual/CI review of one commit using a
  Jira description as intent; diff = `parent(id)..id`. Jira text may also be piped via stdin.
- `review` — dry-run of `HEAD~1..HEAD`.
- `doctor` — environment self-check. `--version`.

`pre-push` and `commit` share the same core pipeline: `ReviewService.reviewRange(...)`. When adding
behaviour, put it there so both entry points benefit. `Main.execute(ReviewOp)` is the shared
runner (config load, credential check, time-budget watchdog, render) — both modes go through it.

## Run locally

```bash
java -jar build/libs/aireview-*-all.jar --version
java -jar build/libs/aireview-*-all.jar doctor
aireview review                         # dry-run review of HEAD~1..HEAD (after install.sh)
aireview commit <id> --jira "PROJ-1: ..."   # review a specific commit
```

## Architecture (package map)

- `Main` — arg dispatch, time-budget watchdog (ExecutorService + `future.get(budget)`), spinner,
  always exit 0.
- `config/` — `Config` POJO + `ConfigLoader` (defaults < global YAML < repo YAML < env). Never
  throws; invalid config logs and falls back.
- `git/` — `PushRef` (parses pre-push stdin lines; zero-OID = delete/new-branch), `GitService`
  (git CLI wrapper with timeouts; **diff pathspecs use `:(glob)` magic** so `**` matches root-level
  files — see the comment in `GitService.diff`, this was a real bug).
- `llm/` — `LlmClient` interface + `MiniMaxClient` (OpenAI-compatible wire format per SPEC §12.6;
  bearer/basic/custom auth; JSON-mode-with-fallback; bounded retry on 429/5xx/timeout).
- `review/` — `ReviewService` (orchestration), `PromptBuilder`, `ResponseParser` (defensive JSON
  extraction — balances braces, ignores string contents, recovers from prose/fences), `Finding`,
  `ReviewResult`.
- `output/` — `Renderer` writes to **stderr** (never stdout; never affects the push).
- `cache/` — diff-hash file cache (`~/.cache/aireview/`).
- `privacy/` — `Redactor` scrubs secrets from the diff before egress (defence-in-depth).
- `doctor/`, `util/` — self-check; JSON/hash/ANSI/log/version helpers.

## Conventions

- Prefer few, high-confidence review findings; false positives erode trust (this is also the
  product's review philosophy — keep the prompt in `PromptBuilder` aligned with it).
- The bundled rubric is `src/main/resources/best-practices-java-spring.md`. Bump
  `Version.PROMPT_TEMPLATE_VERSION` when the prompt/rubric changes (it participates in the cache key).
- Logging (`util/Logs`) must never record diff content or secrets at default level.
- New config keys: add to `Config`, map in `ConfigLoader.apply`, document in SPEC §8.2 and the
  `install.sh` scaffold.

## Tests

JUnit 5. Critical-path coverage lives in `src/test`: `PushRefTest` (ref parsing),
`ResponseParserTest` (messy-JSON recovery), `RedactorTest` (secret scrubbing), `HashingTest`
(cache-key stability). Add tests alongside any change to these areas.
