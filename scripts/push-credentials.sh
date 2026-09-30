#!/usr/bin/env bash
# Push a locally supplied provider credential file into the app's *private*
# storage so a DEBUG build can import it into the AndroidKeyStore-backed store.
#
# The value never touches the repository: secrets/ is gitignored (see
# secrets/README.md). This mirrors the debuggable-app trick in
# scripts/push-models.sh — adb push to /data/local/tmp, then `run-as` to copy
# into app-private files/ — and never prints the secret (size checks only).
#
# Usage (from WSL, repo root):
#   bash scripts/push-credentials.sh
#   SECRETS_FILE=secrets/other.key bash scripts/push-credentials.sh
#
# Env:
#   SECRETS_FILE=<path>   local file to push (default secrets/opencode-go.key)
#   SERIAL=<adb serial>   target a specific device (default: the only one)
#   PACKAGE=com.voicechat.agent   (default)
#
# The on-device destination must match the app-side importer
# (app/src/debug/.../DebugCredentialImport.kt: DIRECTORY_NAME / FILE_NAME).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS_FILE="${SECRETS_FILE:-$REPO_ROOT/secrets/opencode-go.key}"
PACKAGE="${PACKAGE:-com.voicechat.agent}"
ADB_BIN="${ADB:-adb}"
ADB_ARGS=()
[[ -n "${SERIAL:-}" ]] && ADB_ARGS=(-s "$SERIAL")

adb_run() { "$ADB_BIN" "${ADB_ARGS[@]}" "$@" </dev/null; }

# Keep in sync with DebugCredentialImport.DIRECTORY_NAME / FILE_NAME.
DEST_DIR="debug-credentials"
DEST_FILE="opencode-go.key"
DEST="$DEST_DIR/$DEST_FILE"

if ! command -v "$ADB_BIN" >/dev/null 2>&1 && [[ ! -f "$ADB_BIN" ]]; then
  echo "adb not found on PATH; set ADB=/path/to/adb" >&2
  exit 1
fi
if [[ ! -f "$SECRETS_FILE" ]]; then
  echo "missing $SECRETS_FILE; create it (one NAME=value per line) or set SECRETS_FILE" >&2
  exit 1
fi

echo "Verifying app-private storage is reachable for $PACKAGE ..."
if ! adb_run shell run-as "$PACKAGE" id >/dev/null 2>&1; then
  echo "ERROR: 'run-as $PACKAGE' failed. Install the DEBUG build first" >&2
  echo "       (\\gradlew.bat :app:installDebug) and confirm the device is debuggable." >&2
  exit 1
fi
adb_run shell run-as "$PACKAGE" mkdir -p "files/$DEST_DIR"

# The adb binary may be a Windows build (invoked from WSL), which cannot read a
# /mnt/... path; hand it a Windows path when wslpath is available.
src_for_adb="$SECRETS_FILE"
if command -v wslpath >/dev/null 2>&1; then
  src_for_adb="$(wslpath -w "$SECRETS_FILE")"
fi

tmp="/data/local/tmp/voicechat-credentials"
adb_run push "$src_for_adb" "$tmp" >/dev/null
adb_run shell run-as "$PACKAGE" cp "$tmp" "files/$DEST"
adb_run shell rm -f "$tmp"

# Report the installed size so a silent truncation is visible; never the value.
installed="$(adb_run shell run-as "$PACKAGE" stat -c '%s' "files/$DEST" | tr -d '\r')"
local_size="$(stat -c '%s' "$SECRETS_FILE")"
if [[ "$installed" == "$local_size" ]]; then
  echo "OK: pushed $local_size bytes to $PACKAGE files/$DEST"
  echo "The debug build imports it into AndroidKeyStore on next launch, then deletes the file."
else
  echo "SIZE MISMATCH: device has '$installed', expected '$local_size'" >&2
  exit 1
fi
