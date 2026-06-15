# aireview — advisory AI code review on `git push`

A client-side Git `pre-push` hook that sends the pushed diff + repo context (`AGENTS.md` +
a Java/Spring best-practices rubric) to a **self-hosted MiniMax** (OpenAI-compatible / vLLM)
endpoint and prints an **advisory** review to your terminal. It **never blocks a push**.

> MVP. See [`SPEC.md`](SPEC.md) for the full design, scope, and roadmap to the server-side phase.

## What it does / doesn't do

- ✅ Judgment-level review: design, Spring idioms, transactions, logic/edge cases, intent match,
  clarity, and duplication **visible in the diff** (+ a cheap symbol-grep heuristic).
- ❌ Does **not** re-check lint/tests/security (handled by your existing tooling).
- ❌ Does **not** do repo-wide / cross-repo duplicate detection yet (needs the server-side index —
  see SPEC §21).

## Build

```bash
gradle build          # compiles, tests, and produces the fat JAR
# → build/libs/aireview-<version>-all.jar
```

Requires JDK 21+ (the build pins a Java 21 toolchain; the fat JAR is Java 21 bytecode). Built and
tested with Gradle 9. If a JDK 21 isn't installed, the foojay toolchain resolver auto-downloads one.

## Install (per machine)

```bash
./install.sh          # uses build/libs/*-all.jar, or set AIREVIEW_JAR_URL for the internal repo
```

Then edit `~/.config/aireview/.env` and set your credential (default scheme is `bearer`):

```
AIREVIEW_API_TOKEN=<your token>
```

Check everything:

```bash
aireview doctor
```

## Activate in a repo (pilot)

```bash
cd <your-spring-repo>
git config core.hooksPath .githooks   # uses the tracked .githooks/pre-push
```

On the next `git push`, you'll see an advisory review. The push always proceeds.

## Configuration

Global config: `~/.config/aireview/config.yml` (scaffolded by the installer). Repo overrides:
`<repo>/.aireview/config.yml`. Precedence: defaults < global < repo < env vars. See SPEC §8.

Key knobs: `llm.baseUrl`, `llm.model`, `llm.auth.scheme` (bearer|basic|custom),
`guardrails.totalTimeBudgetMs`, `review.max*` size caps, `repositories[]`.

## Review a specific commit (manual / CI entry point)

Review one commit by id, passing the Jira/ticket description as the review **intent**:

```bash
aireview commit <commitId> --jira "PROJ-42: allow partial refunds for cancelled orders"
aireview commit <commitId> --jira-file ./ticket.txt
echo "PROJ-42: ..." | aireview commit <commitId>          # Jira text via stdin
```

The diff reviewed is `parent(commit)..commit`. The Jira text is combined with the commit message
and fed to the model as intent, so it can judge whether the change actually does what the ticket
asked. Like every mode, it is advisory and exits 0.

## Try it without pushing

```bash
aireview review       # dry-run review of HEAD~1..HEAD
```

## Advisory guarantee

By design this tool exits `0` in **every** case — success, findings, timeout, no credentials,
network error, bad config, even an unhandled exception. A reviewer must never break a push.
The failure-mode matrix is in SPEC §14, and there are tests asserting exit 0 across paths.

## Project layout

```
SPEC.md                         full specification
build.gradle / settings.gradle  Gradle (Groovy) build → fat JAR via gradleup shadow
.githooks/pre-push              thin tracked hook (forwards to the CLI)
install.sh                      per-machine installer + config scaffold
src/main/resources/best-practices-java-spring.md   bundled Spring rubric
src/main/java/com/platform/tools/aireview/
  Main.java            entrypoint, time-budget watchdog, spinner, always exit 0
  config/              Config + YAML/env merge
  git/                 pre-push ref parsing + git plumbing
  llm/                 LlmClient + MiniMax (OpenAI-compatible) client
  review/              orchestration, prompt, defensive JSON parsing, domain
  output/              terminal renderer (stderr)
  cache/               diff-hash file cache
  privacy/             secret redaction before egress
  doctor/              environment self-check
```
