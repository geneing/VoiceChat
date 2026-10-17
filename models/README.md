# Downloaded model artifacts (not committed)

This directory holds the pinned on-device model artifacts used by the app and
device evaluations. The **files are deliberately not committed** so only this README,
`manifest.json`, and the scripts that fetch them are tracked (`.gitignore` ignores
`models/*` except `README.md`, `*.json`, and `*.sha256`).

Fetch them with:

```bash
# from WSL, repo root
bash scripts/fetch-models.sh            # all artifacts
bash scripts/fetch-models.sh smart-turn # one artifact by id
```

The script downloads to this directory, verifies the exact byte size and SHA-256
recorded in `scripts/fetch-models.sh`, and is idempotent (a verified file is not
re-downloaded unless `FORCE=1`). It is the single source of truth for the pinned
checksums; `manifest.json` records the same values for tooling.

The former sherpa-onnx Parakeet TDT-CTC 110M candidate lane (its Windows
PowerShell fetcher and app-private push helper) was **retired in M27** along with
the app-managed Parakeet STT engine; see
[`docs/android-speech-recognition.md`](../docs/android-speech-recognition.md). The
LiteRT ASR benchmark lane is separate and stages its own `litert-asr` inputs with
`scripts/stage-litert-asr-inputs.sh`.

Install the remaining artifacts into the app's **private** storage on a device
with:

```bash
bash scripts/push-models.sh             # adb push + run-as into filesDir
```

That path exists so device tests do not re-download models over the network every
run. It is a developer/CI shortcut only: the app's own production mechanism
(download → verify → atomic install into app-private storage) is unchanged, and
release APKs never bundle these files.

## Catalog

| id | file | size (bytes) | SHA-256 | license | source (revision) |
| --- | --- | --- | --- | --- | --- |
| `smart-turn` | `smart-turn-v3.2-int8.onnx` | 11,123,370 | `00cd131551e8d1e9011f31345edb632a26116dd83e159782c4ddff610718ea31` | BSD-2-Clause | `soniqo/Smart-Turn-v3.2-ONNX@b48fdbe20772bcec1fef02f4a1a355236ef6359e` |

No local LLM (`.litertlm`) is shipped: the external OpenCode Go provider is the
primary language-model path. The app-managed local-model catalog is intentionally
empty (`docs/local-models.md`).

Sizes and hashes were read from the publisher's Hugging Face API and were
independently re-verified when the file was fetched (**2026-09-30**). The
description and pinning rationale live in
[`docs/smart-turn.md`](../docs/smart-turn.md).

## Licenses

- **Smart Turn v3.2 ONNX** — BSD-2-Clause, per the publisher's `LICENSE`
  (1,406 bytes) in the model repo.

Downloading, storing, and running these files is the user's responsibility; this
repository only records their identity and integrity. Do not commit the artifacts
or redistribute them from this repository.

