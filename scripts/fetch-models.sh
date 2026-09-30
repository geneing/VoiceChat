#!/usr/bin/env bash
# Fetch the project's pinned on-device model artifacts into ./models.
#
# These are the *app-run* artifacts used to exercise the model paths on a real
# device without re-downloading every test run. They are deliberately NOT
# committed to git (too large); only this script, models/README.md, and the
# manifest/checksums are tracked. See models/README.md for provenance/license.
#
# Behavior:
#   - downloads each artifact to models/ (resumable via curl -C -)
#   - verifies the exact byte size and SHA-256 recorded below
#   - never overwrites a verified file (idempotent/offline-friendly)
#   - exits non-zero on any verification failure
#
# Usage (from WSL, repo root):
#   wsl -e bash -lc "cd /mnt/i/Android_Projects/VoiceChat && bash scripts/fetch-models.sh"
#   bash scripts/fetch-models.sh                 # all artifacts
#   bash scripts/fetch-models.sh smart-turn       # one artifact by id
#   FORCE=1 bash scripts/fetch-models.sh          # re-download even if verified
#
# Requires: curl, sha256sum, stat, and (optionally) python3 for JSON output.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${MODELS_DIR:-$REPO_ROOT/models}"
FORCE="${FORCE:-0}"

# --- pinned catalog ---------------------------------------------------------
# Fields, tab-separated: id | filename | url | size | sha256 | license | source
# The size/sha256 are the exact values from the publishers' APIs (verified
# 2026-09-30). Do not change one without re-verifying and updating the docs.
# Only the Smart Turn semantic end-of-turn model is pinned; no local LLM
# (.litertlm) is shipped (docs/decisions.md, docs/local-models.md).
CATALOG=$(cat <<'EOF'
smart-turn	smart-turn-v3.2-int8.onnx	https://huggingface.co/soniqo/Smart-Turn-v3.2-ONNX/resolve/b48fdbe20772bcec1fef02f4a1a355236ef6359e/smart-turn-v3.2-int8.onnx	11123370	00cd131551e8d1e9011f31345edb632a26116dd83e159782c4ddff610718ea31	BSD-2-Clause	soniqo/Smart-Turn-v3.2-ONNX@b48fdbe20772bcec1fef02f4a1a355236ef6359e
EOF
)

want=""
if [[ $# -gt 0 ]]; then want="$1"; fi

mkdir -p "$MODELS_DIR"

sha256_of() {
  sha256sum "$1" | awk '{print $1}'
}

size_of() {
  # GNU stat (WSL/coreutils)
  stat -c '%s' "$1"
}

verify() {
  local file="$1" size="$2" sha="$3"
  [[ -f "$file" ]] || return 1
  local actual_size actual_sha
  actual_size="$(size_of "$file")"
  if [[ "$actual_size" != "$size" ]]; then
    echo "    size mismatch: got $actual_size, want $size" >&2
    return 1
  fi
  actual_sha="$(sha256_of "$file")"
  if [[ "$actual_sha" != "$sha" ]]; then
    echo "    sha256 mismatch: got $actual_sha, want $sha" >&2
    return 1
  fi
  return 0
}

printf 'Fetching pinned models into %s\n\n' "$MODELS_DIR"

failures=0
while IFS=$'\t' read -r id filename url size sha license source; do
  [[ -n "${id:-}" ]] || continue
  if [[ -n "$want" && "$want" != "$id" ]]; then continue; fi

  dest="$MODELS_DIR/$filename"
  printf '== %s\n   %s (%s bytes, %s)\n' "$id" "$filename" "$size" "$license"

  if [[ "$FORCE" != "1" ]] && verify "$dest" "$size" "$sha" 2>/dev/null; then
    echo "   already verified; skipping (FORCE=1 to re-download)"
    continue
  fi

  echo "   downloading..."
  if ! curl -fL --retry 3 --retry-delay 2 -C - -o "$dest" "$url"; then
    # -C - can confuse a fresh small file; retry once without resume.
    echo "   resume attempt failed; retrying fresh..." >&2
    rm -f "$dest"
    curl -fL --retry 3 --retry-delay 2 -o "$dest" "$url"
  fi

  if verify "$dest" "$size" "$sha"; then
    echo "   OK: size and sha256 verified"
  else
    echo "   VERIFICATION FAILED for $filename" >&2
    failures=$((failures + 1))
  fi
done < <(printf '%s\n' "$CATALOG")

echo
if [[ "$failures" -gt 0 ]]; then
  echo "$failures artifact(s) failed verification." >&2
  exit 1
fi
echo "All requested artifacts are present and verified."
echo
echo "Next: push to the device's app-private storage with"
echo "  bash scripts/push-models.sh          # (see that script for the adb path)"
