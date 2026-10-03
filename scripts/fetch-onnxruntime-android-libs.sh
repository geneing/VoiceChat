#!/usr/bin/env bash
# Extract the pinned ONNX Runtime Android native libraries from the
# onnxruntime-android AAR into models/onnxruntime-android/jniLibs.
#
# Why this exists: Moonshine's debug AAR bundles its own older/smaller
# libonnxruntime.so. Dependency order cannot resolve the collision, so the app
# excludes both libonnxruntime*.so from the merge (app/build.gradle.kts) and
# supplies the pinned runtime from this directory instead. Without it, a clean
# build simply has no ONNX runtime and the Smart Turn / Parakeet-EOU engines
# report unavailable, rather than shipping a library whose JNI cannot load.
#
# The AAR itself is a normal Gradle dependency and is never committed here; only
# the extracted .so files are staged, and models/ is Git-ignored.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
version="${ONNXRUNTIME_VERSION:-1.29.0}"
destination="${DEST:-$repo_root/models/onnxruntime-android/jniLibs}"
jar_bin="${JAR:-jar}"

# Reuse the Gradle-downloaded AAR if present; otherwise fetch it from Maven Central.
aar=""
for candidate in $(find "$HOME/.gradle/caches/modules-2/files-2.1/com.microsoft.onnxruntime/onnxruntime-android/$version" \
    -name "onnxruntime-android-$version.aar" 2>/dev/null); do
  aar="$candidate"
  break
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [[ -z "$aar" ]]; then
  aar="$tmp/onnxruntime-android-$version.aar"
  url="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$version/onnxruntime-android-$version.aar"
  echo "Fetching $url"
  curl -L -sS -o "$aar" "$url"
fi
echo "Using AAR: $aar"

if ! command -v "$jar_bin" >/dev/null 2>&1; then
  echo "JDK 'jar' not found; set JAR=/path/to/jar." >&2
  exit 2
fi

rm -rf "$destination"
mkdir -p "$destination"
(cd "$tmp" && "$jar_bin" --extract --file "$aar")
if [[ ! -d "$tmp/jni" ]]; then
  echo "The AAR has no jni/ directory; unexpected artifact." >&2
  exit 3
fi
cp -r "$tmp/jni/." "$destination/"

echo "Extracted ONNX Runtime $version native libraries into $destination:"
find "$destination" -name '*.so' -exec ls -l {} + | awk '{print $5, $9}'
