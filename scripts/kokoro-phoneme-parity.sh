#!/usr/bin/env bash
# Cross-language phonemizer test: regenerate the Python reference fixtures, then
# assert the Kotlin port against them.
#
# The M28 phonemizer is a port of two Python sources (the publisher's
# `titl/phonemes.py` and Misaki 0.9.4's `misaki/en.py`). A port is only as good
# as the evidence that it still agrees with the original, so this script runs the
# reference to freeze the expected output, then runs the JVM test that compares
# the Kotlin port with it. The test reports the measured sentence-exact agreement
# and the categories that differ; the parts that are literal ports (sentence
# split, `split_phonemes`, `encode`, padding, unknown-symbol dropping) are
# asserted exactly.
#
# Usage (from WSL, repo root):
#   bash scripts/kokoro-phoneme-parity.sh
#
# Env:
#   VENV=~/titl-venv        the Python environment with titl + misaki[en]
#   SKIP_REFERENCE=1        keep the committed fixtures instead of regenerating
#
# Gradle always runs on the Windows host (AGENTS.md), so the test step invokes
# the Windows wrapper through interop.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="${VENV:-$HOME/titl-venv}"
SKIP_REFERENCE="${SKIP_REFERENCE:-0}"

if [[ "$SKIP_REFERENCE" != "1" ]]; then
  if [[ ! -x "$VENV/bin/python" ]]; then
    echo "ERROR: no Python environment at $VENV (set VENV=... or SKIP_REFERENCE=1)." >&2
    exit 1
  fi
  echo "== regenerating the Python reference fixtures"
  "$VENV/bin/python" "$REPO_ROOT/scripts/kokoro-phonemes-reference.py"
else
  echo "== keeping the committed fixtures (SKIP_REFERENCE=1)"
fi

echo
echo "== asserting the Kotlin port against them"
WIN_ROOT="$(wslpath -w "$REPO_ROOT" 2>/dev/null || printf '%s' "$REPO_ROOT")"
powershell.exe -NoProfile -Command "
  \$env:ANDROID_HOME = \"\$env:LOCALAPPDATA\Android\Sdk\";
  Set-Location -LiteralPath '$WIN_ROOT';
  .\gradlew.bat :app:testDebugUnitTest --tests '*KokoroPhonemizerPortTest*'
" || exit $?

echo
echo "The parity test's report is in"
echo "  app/build/reports/tests/testDebugUnitTest/index.html"
echo "and its stdout (the measured agreement rate) is in the test's XML under"
echo "  app/build/test-results/testDebugUnitTest/"
