#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
sample_dir="$repo_root/build/litert-samples-source"
sample_commit="$(tr -d '[:space:]' < "$repo_root/scripts/litert-sample-source.commit")"
sample_url="https://github.com/google-ai-edge/litert-samples.git"
if [[ ! "$sample_commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid pinned LiteRT Samples commit." >&2
  exit 2
fi
if [[ ! -d "$sample_dir/.git" ]]; then
  git clone --filter=blob:none --no-checkout "$sample_url" "$sample_dir"
fi
if ! git -C "$sample_dir" cat-file -e "$sample_commit^0" 2>/dev/null; then
  git -C "$sample_dir" fetch --depth=1 origin "$sample_commit"
fi
git -C "$sample_dir" sparse-checkout init --cone
git -C "$sample_dir" sparse-checkout set samples/litert/speech_recognition
git -C "$sample_dir" checkout --detach "$sample_commit"
actual_commit="$(git -C "$sample_dir" rev-parse HEAD)"
if [[ "$actual_commit" != "$sample_commit" ]]; then
  echo "Pinned checkout mismatch: expected $sample_commit, got $actual_commit" >&2
  exit 3
fi
printf 'sampleCommit=%s\n' "$actual_commit"