#!/usr/bin/env bash
# Local, offline reference transcription for the LiteRT ASR WER harness.
#
# Transcribes the user's test_data speech with a local Whisper model (faster-whisper,
# CPU) and writes one Git-ignored reference text file per clip under
# test_data/litert-asr-wer/. Nothing leaves the machine and no audio or transcript
# is committed. The output is a machine pseudo-reference for agreement scoring,
# not human-reviewed gold truth.
#
# Usage:
#   scripts/local-whisper-reference.sh                 # all test_data/*.wav and Walking*.m4a
#   scripts/local-whisper-reference.sh <file> [...]     # explicit files
# Environment:
#   WHISPER_MODEL   default "small.en" (use "base.en" for a faster, rougher run)
#   WHISPER_VENV    default /tmp/litert-asr-venv
#   WHISPER_DEVICE  default "cpu"; compute type is int8
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
venv="${WHISPER_VENV:-/tmp/litert-asr-venv}"
model="${WHISPER_MODEL:-small.en}"
device="${WHISPER_DEVICE:-cpu}"
output_dir="${OUTPUT_DIR:-$repo_root/test_data/litert-asr-wer}"

if [[ ! -x "$venv/bin/python" ]]; then
  echo "Whisper venv not found at $venv; set WHISPER_VENV." >&2
  exit 2
fi
if [[ $# -gt 0 ]]; then
  files=("$@")
else
  files=()
  while IFS= read -r line; do files+=("$line"); done < <(
    find "$repo_root/test_data" -maxdepth 1 -type f \( -name '*.wav' -o -name 'Walking*.m4a' \) | sort
  )
fi
if [[ ${#files[@]} -eq 0 ]]; then
  echo "No input audio found under $repo_root/test_data." >&2
  exit 3
fi

mkdir -p "$output_dir"
echo "Model=$model device=$device outputs=$output_dir"

"$venv/bin/python" - "$model" "$device" "$output_dir" "${files[@]}" <<'PY'
import subprocess
import sys
from pathlib import Path

import numpy as np
from faster_whisper import WhisperModel

SAMPLE_RATE = 16_000


def decode_with_ffmpeg(path: Path) -> np.ndarray:
    """Decode any container to 16 kHz mono float32 via ffmpeg (av-free path)."""
    result = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path),
         "-f", "f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        check=True,
        stdout=subprocess.PIPE,
    )
    return np.frombuffer(result.stdout, dtype=np.float32).copy()


model_name, device, output_dir, *files = sys.argv[1:]
model = WhisperModel(model_name, device=device, compute_type="int8")
for raw in files:
    path = Path(raw)
    audio = decode_with_ffmpeg(path)
    segments, info = model.transcribe(audio, language="en", beam_size=5)
    text = " ".join(segment.text.strip() for segment in segments).strip()
    target = Path(output_dir) / f"{path.stem}.reference.txt"
    target.write_text(text + "\n", encoding="utf-8")
    duration = len(audio) / SAMPLE_RATE
    print(f"{path.name}\t{info.language}\t{duration:.2f}s\t{len(text.split())} words\t{target.name}")
PY
