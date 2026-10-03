#!/usr/bin/env bash
# Fetch the pinned LiteRT Tensor G5 NPU dispatch library (debug/evaluation only).
#
# The vendor .so is NOT committed; it is installed under the gitignored models/
# tree and is packaged into debug builds only when it is present. This is the
# "Google Tensor" runtime from the LiteRT v2.1.1 release, which is the last
# release that still ships litert_npu_runtime_libraries.zip. See
# docs/litert-speech-benchmark-plan.md for the runtime-version caveat.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${DEST:-$repo_root/models/litert-asr/npu-dispatch}"
url="https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries.zip"
expected_sha256="${EXPECTED_SHA256:-5731f46e9ac6e016113524a7a15fe9feb7bb55e03a0ce1c1b9aee7d5aab46fad}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -L -sS -o "$tmp/npu.zip" "$url"
mkdir -p "$destination/arm64-v8a"
unzip -o -j "$tmp/npu.zip" \
  'google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so' \
  -d "$destination/arm64-v8a" >/dev/null

actual="$(sha256sum "$destination/arm64-v8a/libLiteRtDispatch_GoogleTensor.so" | awk '{print $1}')"
if [[ "$actual" != "$expected_sha256" ]]; then
  echo "Unexpected dispatch library sha256: $actual (expected $expected_sha256)" >&2
  exit 1
fi
printf 'Installed %s\nsha256 %s\n' "$destination/arm64-v8a/libLiteRtDispatch_GoogleTensor.so" "$actual"
