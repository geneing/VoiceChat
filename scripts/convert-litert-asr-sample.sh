#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
sample_dir="$repo_root/build/litert-samples-source"
sample_commit="$(tr -d '[:space:]' < "$repo_root/scripts/litert-sample-source.commit")"
python="${LITERT_ASR_VENV_DIR:-$repo_root/build/litert-asr-venv}/bin/python"

converter="$sample_dir/samples/litert/speech_recognition/convert/convert_to_tflite.py"
usage() {
  cat <<'EOF'
Usage:
  convert-litert-asr-sample.sh MODEL [upstream converter flags...]
Models:
  parakeet-tdt    sample-listed; nvidia/parakeet-tdt-0.6b-v3
  parakeet-ctc    sample-listed; nvidia/parakeet-ctc-0.6b
  moonshine       sample-listed; UsefulSensors/moonshine-tiny
  whisper-tiny    sample-listed; openai/whisper-tiny
  whisper-small   extension candidate; separate wrapper, not sample-listed
Example:
  convert-litert-asr-sample.sh moonshine --sample_audio=test_data/eng-000001-000081dd.wav --input_sec=5 --quant=none --output=models/litert-asr/moonshine-fp32.tflite
Upstream --quant choices are none/drq. FP16 casting and calibrated static INT8
are separate post-conversion steps.
EOF
}
if [[ $# -eq 0 || "$1" == "--help" || "$1" == "-h" ]]; then
  usage
  exit 0
fi
model="$1"
shift
if [[ ! -x "$python" ]]; then
  echo "Isolated Python environment missing; run setup-litert-asr-conversion.sh in WSL." >&2
  exit 4
fi
if [[ ! -f "$converter" ]]; then
  echo "Pinned upstream sample missing; run fetch-litert-speech-sample.sh in WSL." >&2
  exit 5
fi
actual_commit="$(git -C "$sample_dir" rev-parse HEAD)"
if [[ "$actual_commit" != "$sample_commit" ]]; then
  echo "Sample source must be pinned at $sample_commit; found $actual_commit" >&2
  exit 6
fi
output_path=""
audio_path=""
for arg in "$@"; do
  case "$arg" in
    --output=*) output_path="$(printf '%s' "$arg" | cut -d= -f2-)" ;;
    --sample_audio=*) audio_path="$(printf '%s' "$arg" | cut -d= -f2-)" ;;
  esac
done
if [[ -z "$output_path" || -z "$audio_path" ]]; then
  echo "Pass --output=... and --sample_audio=... flags." >&2
  exit 7
fi
if [[ "$output_path" != /* ]]; then output_path="$repo_root/$output_path"; fi
if [[ "$audio_path" != /* ]]; then audio_path="$repo_root/$audio_path"; fi
output_absolute="$(realpath -m -- "$output_path")"
audio_absolute="$(realpath -m -- "$audio_path")"
relative_output="$(realpath -m --relative-to="$repo_root" "$output_absolute")"
case "$relative_output" in
  models/*) ;;
  *) echo "Output must be inside models/." >&2; exit 8 ;;
esac
grep -Fqx 'models/*' "$repo_root/.gitignore" || {
  echo "Root .gitignore must keep the models/* artifact rule (checked from WSL)." >&2
  exit 9
}
case "$audio_absolute/" in
  "$repo_root/test_data/"*) ;;
  *) echo "Sample audio must be inside test_data/." >&2; exit 10 ;;
esac
if [[ ! -f "$audio_absolute" ]]; then
  echo "Sample audio file not found: $audio_absolute" >&2
  exit 11
fi
cd "$repo_root"

case "$model" in
  parakeet-tdt) model_id="nvidia/parakeet-tdt-0.6b-v3" ;;
  parakeet-ctc) model_id="nvidia/parakeet-ctc-0.6b" ;;
  moonshine) model_id="UsefulSensors/moonshine-tiny" ;;
  whisper-tiny) model_id="openai/whisper-tiny" ;;
  whisper-small)
    export PYTHONPATH="$sample_dir"
    exec "$python" "$repo_root/scripts/litert_asr_whisper_small_converter.py" "$@"
    ;;
  *)
    echo "Unsupported model: $model" >&2
    usage >&2
    exit 2
    ;;
esac
export PYTHONPATH="$sample_dir"
# Parakeet CTC needs a tracked compatibility patch for the transformers 5.18
# ParakeetEncoderCTCHead contract; the downloaded sample is left untouched.
case "$model" in
  parakeet-ctc | parakeet-tdt)
    exec "$python" "$repo_root/scripts/litert_asr_parakeet_compat.py" \
      "--model=$model_id" "$@" ;;
esac
exec "$python" "$converter" "--model=$model_id" "$@"
