#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
repo_root="$(realpath -- "$repo_root")"
output_root="/tmp/voicechat-litert-asr-calibration"

if [[ $# -lt 1 ]]; then
  echo "Usage: prepare-litert-asr-calibration.sh test_data/Walking*.m4a [...]" >&2
  exit 2
fi
command -v ffmpeg >/dev/null || { echo "ffmpeg is required in WSL." >&2; exit 3; }
command -v ffprobe >/dev/null || { echo "ffprobe is required in WSL." >&2; exit 3; }

mkdir -p -- "$output_root"
chmod 700 -- "$output_root"
ffmpeg_version="$(ffmpeg -version | head -n 1)"

for input in "$@"; do
  if [[ "$input" != /* ]]; then input="$repo_root/$input"; fi
  input="$(realpath -- "$input")"
  case "$input" in
    "$repo_root"/test_data/Walking*.m4a) ;;
    *) echo "Calibration input must be a user-owned Walking*.m4a file under test_data/: $input" >&2; exit 4 ;;
  esac
  [[ -f "$input" ]] || { echo "Audio file not found: $input" >&2; exit 5; }

  source_sha="$(sha256sum -- "$input" | cut -d ' ' -f 1)"
  output="$output_root/walking-$source_sha.wav"
  sidecar="$output.json"
  ffmpeg -hide_banner -nostdin -loglevel error -y -i "$input" \
    -map_metadata -1 -vn -ac 1 -ar 16000 -c:a pcm_s16le "$output"
  audio_info="$(ffprobe -v error -select_streams a:0 \
    -show_entries stream=sample_rate,channels -show_entries format=duration \
    -of default=noprint_wrappers=1 "$output")"
  grep -Fqx 'sample_rate=16000' <<<"$audio_info"
  grep -Fqx 'channels=1' <<<"$audio_info"
  duration="$(awk -F= '$1 == "duration" { print $2 }' <<<"$audio_info")"
  awk -v duration="$duration" 'BEGIN { exit !(duration >= 5.0) }' || {
    echo "Converted clip is shorter than the five-second calibration window: $input" >&2
    exit 6
  }
  converted_sha="$(sha256sum -- "$output" | cut -d ' ' -f 1)"
  cat > "$sidecar" <<EOF
{
  "source_file": "$(basename -- "$input")",
  "source_sha256": "$source_sha",
  "derived_file": "$(basename -- "$output")",
  "derived_sha256": "$converted_sha",
  "sample_rate_hz": 16000,
  "channels": 1,
  "sample_format": "pcm_s16le",
  "duration_seconds": $duration,
  "decoder": "$ffmpeg_version",
  "provenance": "User-described outdoor recording, decoded and resampled locally for calibration; no audio upload or redistribution."
}
EOF
  chmod 600 -- "$output" "$sidecar"
  printf 'prepared=%s\nsourceSha256=%s\noutputSha256=%s\ndurationSeconds=%s\n' \
    "$output" "$source_sha" "$converted_sha" "$duration"
done
