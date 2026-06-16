# ROADMAP — aireview

What's intentionally **deferred** out of the MVP, why, and when we'd pick it up. The MVP is the
client-side `pre-push` advisory reviewer described in [`SPEC.md`](SPEC.md). This file is the running
log of "not yet" decisions so nothing gets silently dropped.

Status legend: 🔜 next · 🧊 later · 💭 idea / unvalidated

---

## Review context (the "related code" problem)

The reviewer's quality is bounded by how much relevant code it can see. We're building this up in
layers (see the conversation that produced this decision).

- ✅ **L1 — full changed-file bodies.** Send the whole post-change body of each changed file
  alongside the diff, budget-capped, redacted, with overflow degrading to signatures-only.
  *(Shipped — `review.relatedCode.*`, `GitService.changedFiles`/`fileAtCommit`.)*
- 🔜 **L2 — first-degree neighbours.** Resolve Java `import com.platform.*` in changed files to
  same-repo file paths and include the relevant ones (implemented interfaces, extended superclasses,
  DTOs, called services). Catches *contract drift* (impl changed, interface not) and real
  duplication. Natural first slice: upgrade `symbolGrep` to return the **definition snippet** around
  each hit instead of a one-line grep match.
  *Why deferred:* want to see how much of the quality gap L1 alone closes before paying the token /
  latency / complexity cost of import-graph resolution.
- 🧊 **L3 — repo-wide / cross-repo semantic context.** Embeddings + an index for true repo-wide
  duplicate detection and "this already exists elsewhere on the platform." Requires the server-side
  phase below. *Why deferred:* impossible to do well client-side and synchronously within the time
  budget; needs a persistent index.

## Architecture

- 🧊 **Server-side `post-receive` migration.** The MVP is client-side + synchronous + every-push
  (SPEC §21). If the MVP succeeds, move to a server-side `post-receive` hook with a persistent repo
  index, which unlocks L3 above and removes the per-developer credential/setup burden.
  *Why deferred:* MVP-first — prove value before standing up server infrastructure.
- 🧊 **Multi-repo rollout.** Config already models `repositories[]` as an array, but the pilot runs
  on **one** repo. Expand to the 5 functional + 4 supporting repos once the pilot is validated.

## Distribution / ops

- 🔜 **Push this repo to a git remote.** Still outstanding — blocked on a remote URL / `gh` auth.
- 🔜 **Internal artifact hosting.** Publish the fat JAR to internal Nexus/Artifactory and wire
  `AIREVIEW_JAR_URL` in `install.sh` (TODO marker in `build.gradle`). Lets machines install without
  a local build.
- 🧊 **Telemetry.** `telemetry.*` config exists but is disabled and unimplemented — opt-in usage /
  latency / finding-rate metrics to measure whether the reviewer is trusted and useful.

## Review quality

- 💭 **Signal feedback loop.** No way yet for a developer to mark a finding as a false positive and
  have that improve future reviews. Important for the "false positives erode trust" risk.
- 💭 **Severity-gated output / partial-on-timeout.** `guardrails.onTimeout=partial` and
  `output.minSeverity` exist as knobs but the partial-result path is minimal; revisit once we have
  real-world latency data.

---

When you defer something, add it here with a one-line *why*. When you ship a deferred item, mark it
✅ and note where it landed.
