#!/usr/bin/env bash
# Fetch the pinned Kokoro phoneme data used by the ported English G2P.
#
# Two kinds of data come from third parties and are NOT hand-written here:
#
#   * `vocab.json`  - Kokoro-82M's phoneme vocabulary, from the pinned
#                     `MithrilMan/teacher-in-the-loop` revision (Apache-2.0).
#   * `us_gold.json.gz` / `us_silver.json.gz` - Misaki's English lexicon
#                     (`misaki/data/us_{gold,silver}.json`, Apache-2.0), which is
#                     the lexicon Kokoro's own pipeline pronounces English words
#                     from. Committed gzipped (~1.4 MB together) so the on-device
#                     phonemizer works offline with no extra download.
#
# Usage (from WSL, repo root):
#   bash scripts/fetch-kokoro-phoneme-data.sh
#
# Requires: curl, gzip, python3 (for the wheel download).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="${ASSETS_DIR:-$REPO_ROOT/app/src/main/assets/kokoro}"
REVISION="115a6c0469632a84fbcc6c8ed0ad7b1e06154b92"
HF_BASE="https://huggingface.co/MithrilMan/teacher-in-the-loop/resolve/$REVISION"
MISAKI_VERSION="0.9.4"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$ASSETS_DIR"

echo "== vocab.json ($REVISION)"
curl -fL --retry 3 -o "$ASSETS_DIR/vocab.json" "$HF_BASE/vocab.json"
python3 - "$ASSETS_DIR/vocab.json" <<'PY'
import hashlib, sys
path = sys.argv[1]
raw = open(path, 'rb').read()
want = "c985ed32b1007e0d52c8e86dd3f54e3ec62b74d14212bb5718348f7e9d734acf"
got = hashlib.sha256(raw).hexdigest()
assert len(raw) == 1147, (len(raw), path)
assert got == want, (got, want)
print("   OK: %d bytes, sha256 verified" % len(raw))
PY

echo "== misaki $MISAKI_VERSION lexicon"
python3 -m pip download "misaki==$MISAKI_VERSION" --no-deps -d "$WORK_DIR" >/dev/null
python3 - "$WORK_DIR" "$ASSETS_DIR" <<'PY'
import glob, gzip, os, sys, zipfile
work, assets = sys.argv[1], sys.argv[2]
wheel = glob.glob(os.path.join(work, "misaki-*.whl"))
if not wheel:
    raise SystemExit("misaki wheel not downloaded")
with zipfile.ZipFile(wheel[0]) as z:
    for name in ("us_gold.json", "us_silver.json"):
        raw = z.read("misaki/data/" + name)
        target = os.path.join(assets, name + ".gz")
        with gzip.GzipFile(filename="", mode="wb", fileobj=open(target, "wb"), compresslevel=9, mtime=0) as out:
            out.write(raw)
        print("   %s: %d bytes -> %d gzipped" % (name, len(raw), os.path.getsize(target)))
PY

echo
echo "Wrote:"
ls -l "$ASSETS_DIR"
