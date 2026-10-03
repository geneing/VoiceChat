#!/usr/bin/env bash
# Fetch the pinned jlibrosa JAR used by the LiteRT speech sample's mel front end.
#
# Vendored under the gitignored models/ tree; the repo does not commit it. The
# published "jar-with-dependencies" also bundles junit, maven, and commons
# classes that collide with the app's test dependencies, so this script
# repackages it keeping only jlibrosa's own classes plus the math/commons deps it
# actually needs at runtime.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${DEST:-$repo_root/models/litert-asr/jlibrosa/libs}"
url="https://github.com/Subtitle-Synchronizer/jlibrosa/raw/master/binaries/jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar"
expected_sha256="${EXPECTED_SHA256:-dc6ec9537f6e1e4f300b4021e214cc7f22bc661161d3a64ecd57b6a113a6a077}"
file_name="jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar"
jar_bin="${JAR:-jar}"

mkdir -p "$destination"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -L -sS -o "$tmp/original.jar" "$url"
actual="$(sha256sum "$tmp/original.jar" | awk '{print $1}')"
if [[ "$actual" != "$expected_sha256" ]]; then
  echo "Unexpected jlibrosa sha256: $actual (expected $expected_sha256)" >&2
  exit 1
fi

if ! command -v "$jar_bin" >/dev/null 2>&1; then
  echo "JDK 'jar' not found; set JAR=/path/to/jar." >&2
  exit 2
fi

mkdir -p "$tmp/extract"
(cd "$tmp/extract" && "$jar_bin" --extract --file "$tmp/original.jar")

# Remove the bundled test/build tooling that collides with the app's own
# dependencies. jlibrosa's runtime deps (commons-math3 and friends) are kept.
rm -rf \
  "$tmp/extract/junit" \
  "$tmp/extract/org/junit" \
  "$tmp/extract/org/hamcrest" \
  "$tmp/extract/META-INF/maven"

if [[ ! -d "$tmp/extract/com/jlibrosa" ]]; then
  echo "Repackaged jar is missing com/jlibrosa classes." >&2
  exit 3
fi

(cd "$tmp/extract" && "$jar_bin" --create --file "$destination/$file_name" --no-manifest .)
reduced_sha="$(sha256sum "$destination/$file_name" | awk '{print $1}')"
printf 'jlibrosa=%s\nupstreamSha256=%s\nreducedSha256=%s\n' \
  "$destination/$file_name" "$actual" "$reduced_sha"
