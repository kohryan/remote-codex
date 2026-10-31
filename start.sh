#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export REMOTE_CODEX_HOST="${REMOTE_CODEX_HOST:-0.0.0.0}"
exec node server/main.mjs
