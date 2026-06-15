#!/usr/bin/env bash
# install.sh — per-machine installer for aireview (MVP).
# Installs the launcher + fat JAR and scaffolds config. Idempotent.
set -euo pipefail

VERSION="${AIREVIEW_VERSION:-0.1.0}"
SHARE_DIR="$HOME/.local/share/aireview"
BIN_DIR="$HOME/.local/bin"
CFG_DIR="$HOME/.config/aireview"
JAR_DEST="$SHARE_DIR/aireview.jar"

log() { printf 'aireview-install: %s\n' "$*"; }

uninstall() {
  log "removing launcher and jar"
  rm -f "$BIN_DIR/aireview" "$JAR_DEST"
  if [ "${1:-}" = "--purge" ]; then
    log "purging config and cache"
    rm -rf "$CFG_DIR" "$HOME/.cache/aireview"
  fi
  log "done."
  exit 0
}

[ "${1:-}" = "--uninstall" ] && uninstall "${2:-}"

# 1. JVM check
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java 17+ is required but 'java' was not found on PATH." >&2
  exit 1
fi

mkdir -p "$SHARE_DIR" "$BIN_DIR" "$CFG_DIR"

# 2. JAR: prefer a locally built artifact, else fetch from internal repo.
LOCAL_JAR="$(ls -1 build/libs/aireview-*-all.jar 2>/dev/null | head -n1 || true)"
if [ -n "$LOCAL_JAR" ]; then
  log "installing local build: $LOCAL_JAR"
  cp "$LOCAL_JAR" "$JAR_DEST"
elif [ -n "${AIREVIEW_JAR_URL:-}" ]; then
  log "downloading JAR from $AIREVIEW_JAR_URL"
  curl -fsSL "$AIREVIEW_JAR_URL" -o "$JAR_DEST"
else
  echo "ERROR: no local build found and AIREVIEW_JAR_URL not set." >&2
  echo "Build first:  ./gradlew shadowJar      (then re-run install.sh)" >&2
  exit 1
fi

# 3. Launcher
cat > "$BIN_DIR/aireview" <<'EOF'
#!/bin/sh
set -u
JAR="${AIREVIEW_JAR:-$HOME/.local/share/aireview/aireview.jar}"
if [ -f "$HOME/.config/aireview/.env" ]; then
  set -a; . "$HOME/.config/aireview/.env"; set +a
fi
exec java -jar "$JAR" "$@"
EOF
chmod +x "$BIN_DIR/aireview"
log "launcher installed at $BIN_DIR/aireview"

# 4. Scaffold config (do not overwrite existing)
if [ ! -f "$CFG_DIR/config.yml" ]; then
  cat > "$CFG_DIR/config.yml" <<'EOF'
schemaVersion: 1
llm:
  provider: minimax
  baseUrl: "https://myllm.com/minimax-m2/v1"
  chatPath: "/chat/completions"
  model: "/app/models/MiniMax-M2.5"
  auth:
    scheme: "bearer"
    header: "Authorization"
    preEncoded: false
  temperature: 0.1
  maxOutputTokens: 1500
  requestTimeoutMs: 18000
  maxRetries: 1
repositories:
  - name: "pilot"
    match:
      path: null
      remoteUrl: null
    trunkBranch: "main"
    agentsFile: "AGENTS.md"
    enabled: true
review:
  trunkBranch: "main"
guardrails:
  totalTimeBudgetMs: 22000
  cache:
    enabled: true
    ttlHours: 168
privacy:
  redactSecrets: true
output:
  format: "pretty"
  color: "auto"
  minSeverity: "info"
EOF
  log "wrote $CFG_DIR/config.yml"
fi

if [ ! -f "$CFG_DIR/.env" ]; then
  cat > "$CFG_DIR/.env" <<'EOF'
# Fill in the credential matching llm.auth.scheme (default: bearer)
AIREVIEW_API_TOKEN=
# For scheme=basic instead:
# AIREVIEW_API_USER=
# AIREVIEW_API_PASSWORD=
EOF
  chmod 600 "$CFG_DIR/.env"
  log "wrote $CFG_DIR/.env (chmod 600) — add your token"
fi

# 5. Self-check
log "running doctor…"
"$BIN_DIR/aireview" doctor || true

log "done. Ensure $BIN_DIR is on your PATH."
log "Activate the hook in a repo:  git config core.hooksPath .githooks"
