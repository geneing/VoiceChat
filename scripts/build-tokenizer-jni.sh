#!/usr/bin/env bash
# Build the LiteRT tokenizer JNI bridge for arm64-v8a.
#
# Links our tracked shim (scripts/litert-tokenizer-jni) with the pinned sample's
# prebuilt libtokenizers_c.a + header. The `.a` and header come from the sample
# checkout fetched by scripts/fetch-litert-speech-sample.sh; the sample tree is
# not modified. The result is copied to the gitignored models/ tree so the debug
# APK can decode token IDs into text. Nothing here is committed as a binary.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sample_commit="$(tr -d '[:space:]' < "$repo_root/scripts/litert-sample-source.commit")"
sample_dir="${SAMPLE_DIR:-$repo_root/build/litert-samples-source}"
sample_cpp_dir="$sample_dir/samples/litert/speech_recognition/AndroidApp/app/src/main/cpp"
shim_dir="$repo_root/scripts/litert-tokenizer-jni"
output_dir="${OUTPUT_DIR:-$repo_root/models/litert-asr/tokenizer-jni/arm64-v8a}"
build_dir="${BUILD_DIR:-$repo_root/build/tokenizer-jni}"
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
cmake_bin="${CMAKE_BIN:-cmake}"
ninja_bin="${NINJA_BIN:-ninja}"
abi="${ANDROID_ABI:-arm64-v8a}"
platform="${ANDROID_PLATFORM:-android-31}"

if [[ ! -f "$sample_cpp_dir/include/tokenizers_c.h" || ! -f "$sample_cpp_dir/lib/$abi/libtokenizers_c.a" ]]; then
  echo "Pinned sample tokenizer inputs missing under $sample_cpp_dir." >&2
  echo "Run scripts/fetch-litert-speech-sample.sh first (commit $sample_commit)." >&2
  exit 2
fi
if [[ -z "$ndk" || ! -f "$ndk/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found; set ANDROID_NDK_HOME to an NDK with build/cmake/android.toolchain.cmake." >&2
  exit 3
fi
if ! command -v "$cmake_bin" >/dev/null 2>&1; then
  echo "cmake not found; set CMAKE_BIN." >&2
  exit 4
fi
if ! command -v "$ninja_bin" >/dev/null 2>&1; then
  echo "ninja not found; set NINJA_BIN." >&2
  exit 5
fi

rm -rf "$build_dir"
mkdir -p "$build_dir" "$output_dir"

# A Windows cmake.exe/ninja.exe (the Android SDK copies) cannot read WSL /mnt
# paths and needs Windows paths for -S/-B. Translate when the tool is a Windows
# binary and wslpath is available, matching scripts/stage-litert-asr-inputs.sh.
to_tool_path() {
  if [[ "$cmake_bin" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

# Forward the two paths as cache variables. A Windows cmake.exe receives
# Windows-style values and normalizes them with file(TO_CMAKE_PATH) in the
# CMakeLists; a WSL cmake receives POSIX values unchanged.
forward_path() {
  if [[ "$cmake_bin" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

"$cmake_bin" \
  -G Ninja \
  -S "$(to_tool_path "$shim_dir")" \
  -B "$(to_tool_path "$build_dir")" \
  -DCMAKE_MAKE_PROGRAM="$(to_tool_path "$(command -v "$ninja_bin")")" \
  -DCMAKE_TOOLCHAIN_FILE="$(to_tool_path "$ndk/build/cmake/android.toolchain.cmake")" \
  -DANDROID_ABI="$abi" \
  -DANDROID_PLATFORM="$platform" \
  -DCMAKE_BUILD_TYPE=Release \
  -DVOICECHAT_TOKENIZER_SOURCE_DIR="$(forward_path "$shim_dir")" \
  -DVOICECHAT_TOKENIZER_SAMPLE_CPP_DIR="$(forward_path "$sample_cpp_dir")" >/dev/null
"$ninja_bin" -C "$(to_tool_path "$build_dir")" >/dev/null

built_so="$build_dir/libtokenizer_jni.so"
if [[ ! -f "$built_so" ]]; then
  echo "Build did not produce libtokenizer_jni.so." >&2
  exit 6
fi

cp -f "$built_so" "$output_dir/libtokenizer_jni.so"
built_size="$(stat -c '%s' "$output_dir/libtokenizer_jni.so")"
built_sha="$(sha256sum "$output_dir/libtokenizer_jni.so" | awk '{print $1}')"
printf 'tokenizerJni=%s\nabi=%s\nsize=%s\nsha256=%s\nsampleCommit=%s\n' \
  "$output_dir/libtokenizer_jni.so" "$abi" "$built_size" "$built_sha" "$sample_commit"
