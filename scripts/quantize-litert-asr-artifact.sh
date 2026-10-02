#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
sample_dir="$repo_root/build/litert-samples-source"
sample_commit="$(tr -d '[:space:]' < "$repo_root/scripts/litert-sample-source.commit")"
python="${LITERT_ASR_VENV_DIR:-$repo_root/build/litert-asr-venv}/bin/python"

if [[ ! -x "$python" ]]; then
  echo "Isolated Python environment missing; run setup-litert-asr-conversion.sh in WSL." >&2
  exit 4
fi
if [[ ! -f "$sample_dir/samples/litert/speech_recognition/convert/asr_model.py" ]]; then
  echo "Pinned sample source missing; run fetch-litert-speech-sample.sh in WSL." >&2
  exit 5
fi
actual_commit="$(git -C "$sample_dir" rev-parse HEAD)"
if [[ "$actual_commit" != "$sample_commit" ]]; then
  echo "Sample source must be pinned at $sample_commit; found $actual_commit" >&2
  exit 6
fi

export PYTHONPATH="$sample_dir${PYTHONPATH:+:$PYTHONPATH}"
exec "$python" "$script_dir/quantize_litert_asr_artifact.py" "$@"
