#!/usr/bin/env bash
# Stage one ignored LiteRT model and WAV fixture in the debuggable app's private files.
# Run after installing the debug app; Android test installation may wipe files/.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
package="${PACKAGE:-com.voicechat.agent}"
adb_bin="${ADB:-adb}"
serial="${SERIAL:-}"
model="$repo_root/models/litert-asr/moonshine-tiny-fp32-5s.tflite"
audio="$repo_root/test_data/eng-000001-000081dd.wav"
model_sha256="c4f4735852d4a8de1003431c43a253376c4c91ee19713778a07bd0a446c7e930"
model_bytes=109330616
destination="litert-asr"

if ! command -v "$adb_bin" >/dev/null 2>&1 && [[ ! -f "$adb_bin" ]]; then
  echo "adb not found; set ADB=/path/to/adb" >&2
  exit 1
fi
if [[ ! -f "$model" || ! -f "$audio" ]]; then
  echo "Missing model or audio. Convert Moonshine Tiny first; see docs/Tests.md." >&2
  exit 1
fi
actual_sha256="$(sha256sum "$model" | awk '{print $1}')"
actual_bytes="$(stat -c '%s' "$model")"
if [[ "$actual_sha256" != "$model_sha256" || "$actual_bytes" != "$model_bytes" ]]; then
  echo "Unexpected model artifact: size=$actual_bytes sha256=$actual_sha256" >&2
  exit 1
fi

adb_args=()
if [[ -n "$serial" ]]; then adb_args=(-s "$serial"); fi
adb_run() { "$adb_bin" "${adb_args[@]}" "$@" </dev/null; }

if [[ -z "$serial" ]]; then
  connected="$(adb_run devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$connected" != 1 ]]; then
    echo "Expected one attached adb device, found $connected; set SERIAL when multiple are attached." >&2
    exit 1
  fi
fi
if ! adb_run shell run-as "$package" id >/dev/null 2>&1; then
  echo "run-as $package failed; install the debuggable app before staging." >&2
  exit 1
fi
adb_run shell run-as "$package" mkdir -p "files/$destination"

copy_private() {
  local source="$1"
  local filename="$2"
  local source_for_adb="$source"
  local temporary="/data/local/tmp/voicechat-litert-asr-$filename"
  if [[ "$adb_bin" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    source_for_adb="$(wslpath -w "$source")"
  fi
  adb_run push "$source_for_adb" "$temporary" >/dev/null
  adb_run shell run-as "$package" cp "$temporary" "files/$destination/$filename"
  adb_run shell rm -f "$temporary"
  local expected_size
  local installed_size
  expected_size="$(stat -c '%s' "$source")"
  installed_size="$(adb_run shell run-as "$package" stat -c '%s' "files/$destination/$filename" | tr -d '\r')"
  if [[ "$installed_size" != "$expected_size" ]]; then
    echo "Size mismatch for $filename: expected $expected_size, got $installed_size" >&2
    exit 1
  fi
  printf 'Staged files/%s/%s (%s bytes)\n' "$destination" "$filename" "$installed_size"
}

copy_private "$model" "moonshine-tiny-fp32-5s.tflite"
copy_private "$audio" "eng-000001-000081dd.wav"
