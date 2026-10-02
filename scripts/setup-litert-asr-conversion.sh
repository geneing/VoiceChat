#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
venv="$repo_root/build/litert-asr-venv"
requirements="$repo_root/scripts/litert-asr-conversion-requirements.txt"
if [[ ! -x "$venv/bin/python" ]]; then
  python3 -m venv "$venv"
fi
"$venv/bin/python" -m pip install -r "$requirements"
"$venv/bin/python" -m pip check
"$venv/bin/python" -m pip freeze --all > "$repo_root/build/litert-asr-conversion.freeze.txt"
"$venv/bin/python" --version
printf 'environmentFreeze=%s\n' "$repo_root/build/litert-asr-conversion.freeze.txt"