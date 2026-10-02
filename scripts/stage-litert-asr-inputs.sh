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
fp16_model="$repo_root/models/litert-asr/moonshine-tiny-fp16-5s.tflite"
fp16_sha256="59a3701c307eb0d6ec6d3dc40654b8de3a3c66d67647cd43c25f2aa941d39826"
fp16_bytes=55271072
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

fp16_present=false
if [[ -f "$fp16_model" ]]; then
  fp16_sha256_actual="$(sha256sum "$fp16_model" | awk '{print $1}')"
  fp16_bytes_actual="$(stat -c '%s' "$fp16_model")"
  if [[ "$fp16_sha256_actual" != "$fp16_sha256" || "$fp16_bytes_actual" != "$fp16_bytes" ]]; then
    echo "Unexpected FP16 artifact: size=$fp16_bytes_actual sha256=$fp16_sha256_actual" >&2
    exit 1
  fi
  fp16_present=true
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
if [[ "$fp16_present" == true ]]; then
  copy_private "$fp16_model" "moonshine-tiny-fp16-5s.tflite"
fi

copy_validated() {
  local path="$1" expected_sha="$2" expected_bytes="$3" dest="$4"
  if [[ ! -f "$path" ]]; then
    echo "Missing staged artifact: $path" >&2
    exit 1
  fi
  local actual_sha actual_bytes
  actual_sha="$(sha256sum "$path" | awk '{print $1}')"
  actual_bytes="$(stat -c '%s' "$path")"
  if [[ "$actual_sha" != "$expected_sha" || "$actual_bytes" != "$expected_bytes" ]]; then
    echo "Unexpected artifact $path: size=$actual_bytes sha256=$actual_sha" >&2
    exit 1
  fi
  copy_private "$path" "$dest"
}

# Parakeet TDT/CTC comparison: official litert-community INT8 artifacts plus the
# host-extracted sample features for each family.
if [[ "${STAGE_PARAKEET:-0}" == "1" ]]; then
  copy_validated \
    "$repo_root/models/litert-asr/parakeet-tdt-0.6b-v3-5s-i8-stateful.tflite" \
    "334745b8bc7fd372b1c213516f0b6338bb827b1a2abb3e77ad35fe6fea5cd16b" \
    614261072 "parakeet-tdt-0.6b-v3-5s-i8-stateful.tflite"
  copy_validated \
    "$repo_root/models/litert-asr/parakeet-ctc-0.6b-5s-i8.tflite" \
    "3d0799f625842fded4580c9b51d321667f0bd5f334cb325fb1b9051c6534e102" \
    596365072 "parakeet-ctc-0.6b-5s-i8.tflite"
  copy_validated \
    "$repo_root/models/litert-asr/parakeet-tdt-features-5s.f32" \
    "8434cd7f5cdfd562de7cb777b908ec07fa1307946b25fa7b095d13baeb1fd441" \
    256000 "parakeet-tdt-features-5s.f32"
  copy_validated \
    "$repo_root/models/litert-asr/parakeet-ctc-features-5s.f32" \
    "647222dfb72e123aedbd8a4244079b779af94a4184d6aa3c63ab1ec506b8c460" \
    160000 "parakeet-ctc-features-5s.f32"
fi
