#!/usr/bin/env bash
# Push downloaded model artifacts into the app's *private* storage on a device.
#
# This exists so device tests do not re-download models over the network every
# run: fetch once with scripts/fetch-models.sh, then push. It uses the standard
# debuggable-app trick — `adb push` to /data/local/tmp, then `run-as <pkg>` to
# copy into the app-private files dir — so no storage permission and no rooted
# device are needed. The debug build is debuggable; a release build rejects
# `run-as`, which is expected.
#
# Usage (from WSL, repo root):
#   wsl -e bash -lc "cd /mnt/i/Android_Projects/VoiceChat && bash scripts/push-models.sh"
#   bash scripts/push-models.sh                 # all artifacts
#   bash scripts/push-models.sh smart-turn      # one artifact by id
#
# Env:
#   SERIAL=<adb serial>   target a specific device (default: the only one)
#   PACKAGE=com.voicechat.agent   (default)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${MODELS_DIR:-$REPO_ROOT/models}"
PACKAGE="${PACKAGE:-com.voicechat.agent}"
ADB_BIN="${ADB:-adb}"
ADB_ARGS=()
[[ -n "${SERIAL:-}" ]] && ADB_ARGS=(-s "$SERIAL")

adb_run() { "$ADB_BIN" "${ADB_ARGS[@]}" "$@" </dev/null; }

# --- catalog: id | local filename | app-private destination (relative to files/) ---
# TAB-separated. Kept as a here-doc read through a process substitution so the
# final line is never dropped for lack of a trailing newline.
CATALOG=$'smart-turn\tsmart-turn-v3.2-int8.onnx\tsmart-turn/smart-turn-v3.2-int8.onnx'

want=""
if [[ $# -gt 0 ]]; then want="$1"; fi

if ! command -v "$ADB_BIN" >/dev/null 2>&1 && [[ ! -f "$ADB_BIN" ]]; then
  echo "adb not found on PATH; set ADB=/path/to/adb" >&2
  exit 1
fi

echo "Verifying app-private storage is reachable for $PACKAGE ..."
if ! adb_run shell run-as "$PACKAGE" id >/dev/null 2>&1; then
  echo "ERROR: 'run-as $PACKAGE' failed. Install the DEBUG build first" >&2
  echo "       (\\gradlew.bat :app:installDebug) and confirm the device is debuggable." >&2
  exit 1
fi
# The files dir may not exist on a fresh install; create it once here.
adb_run shell run-as "$PACKAGE" mkdir -p files

failures=0
while IFS=$'\t' read -r id filename dest; do
  [[ -n "${id:-}" ]] || continue
  if [[ -n "$want" && "$want" != "$id" ]]; then continue; fi

  src="$MODELS_DIR/$filename"
  if [[ ! -f "$src" ]]; then
    echo "== $id: SKIP (missing $src; run scripts/fetch-models.sh first)" >&2
    failures=$((failures + 1))
    continue
  fi

  printf '== %s -> files/%s (%s)\n' "$id" "$dest" "$(stat -c '%s' "$src")"
  tmp="/data/local/tmp/voicechat-$filename"
  # The adb binary may be a Windows build (invoked from WSL), which cannot read
  # a /mnt/... path; hand it a Windows path when wslpath is available.
  src_for_adb="$src"
  if command -v wslpath >/dev/null 2>&1; then
    src_for_adb="$(wslpath -w "$src")"
  fi
  adb_run push "$src_for_adb" "$tmp" >/dev/null
  # mkdir -p the parent, then copy from tmp into app-private storage.
  parent="$(dirname "$dest")"
  adb_run shell run-as "$PACKAGE" mkdir -p "files/$parent"
  adb_run shell run-as "$PACKAGE" cp "$tmp" "files/$dest"
  adb_run shell rm -f "$tmp"
  # Report the installed size so a silent truncation is visible.
  installed="$(adb_run shell run-as "$PACKAGE" stat -c '%s' "files/$dest" | tr -d '\r')"
  if [[ "$installed" == "$(stat -c '%s' "$src")" ]]; then
    echo "   OK: $installed bytes in app-private storage"
  else
    echo "   SIZE MISMATCH: device has '$installed'" >&2
    failures=$((failures + 1))
  fi
done < <(printf '%s\n' "$CATALOG")

echo
if [[ "$failures" -gt 0 ]]; then
  echo "$failures artifact(s) were not pushed." >&2
  exit 1
fi
echo "All requested artifacts are in $PACKAGE private storage."
