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
bash scripts/fetch-models.sh kokoro-af-heart
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
| `kokoro-af-heart` | `kokoro-af-heart-model_int8.onnx` | 9,168,506 | `f3daed94c8fe1f7a90f14cd7fc8ead55c99a8e57dd0aac669e376882483222c7` | Apache-2.0 | `MithrilMan/teacher-in-the-loop@115a6c0469632a84fbcc6c8ed0ad7b1e06154b92` |

## Distilled Kokoro English TTS (M28)

The `kokoro-af-heart` artifact is the single-voice, 8.07-million-parameter
`af_heart` English student distilled from Kokoro-82M. Publisher MithrilMan's
model is based on Fabio Angela's 2026 paper, [*Teacher in the Loop: Reliable
Distillation of Kokoro-82M into 8M-Parameter Single-Voice Students, in English
and Italian*](https://doi.org/10.5281/zenodo.23400040), and is published under
Apache-2.0. The graph is pinned to revision
`115a6c0469632a84fbcc6c8ed0ad7b1e06154b92`; it takes padded phoneme ids plus a
speed scalar and returns mono 24 kHz float audio. It is one int8-weight ONNX
graph, not a generic Kokoro runtime or model bundle. See
[`docs/model-runtime.md`](../docs/model-runtime.md) and
[`docs/decisions.md`](../docs/decisions.md) §3.4 for the selection and contract.

The graph is fetched to `models/` only for development/device staging. The app
downloads it into app-private storage, verifies its exact size and SHA-256,
installs it atomically, and can remove it; the graph is never packaged in the
APK. The phoneme vocabulary and Misaki English lexicon are separate app assets
fetched by `scripts/fetch-kokoro-phoneme-data.sh`. Italian student graphs
(`if_sara`, `im_nicola`) are published upstream but are not wired: this app has
no Italian phonemizer.

### ONNX contract verification

Run `~/titl-venv/bin/python scripts/kokoro-onnx-contract.py` from the WSL repo
root with the shared venv dependencies (`onnxruntime` and `numpy`). It downloads
the pinned graph and vocabulary into a temporary directory under `/tmp`, checks
both identities, prints the graph's actual tensor metadata, and runs the same
hand-built phoneme ids twice. Direct WSL `curl` downloads verified the graph at
9,168,506 bytes / SHA-256
`f3daed94c8fe1f7a90f14cd7fc8ead55c99a8e57dd0aac669e376882483222c7` and the
vocabulary at 1,147 bytes / SHA-256
`c985ed32b1007e0d52c8e86dd3f54e3ec62b74d14212bb5718348f7e9d734acf`.

The contract script ran on WSL with Python ONNX Runtime 1.31.0 and NumPy 2.5.4
using the CPU provider. It reported `input_ids` `tensor(int64)` `[1, 'tokens']`,
`speed` `tensor(float)` `[1]`, and `waveform` `tensor(float)`
`['Squeezewaveform_dim_0', 'samples']`. For the hand-built ids
`[0, 50, 83, 156, 54, 57, 135, 0]` (symbols `h ə ˈ l o ʊ`), inference returned
float32 shape `[1, 24600]`, or 1.025 seconds at 24 kHz; min/max/mean were
`-0.210358083 / 0.340811878 / -0.000342661`, and the output was non-silent.
Repeating the same ids was not bit-identical: max absolute delta `0.025423080`,
mean absolute delta `0.000882367`, RMS delta `0.002141009`. This confirms the
graph contract under Python ONNX Runtime 1.31.0, not Android ONNX Runtime 1.29.0
or Pixel 10 performance; see R-0267/R-0268 in
[`docs/risks-and-decisions.md`](../docs/risks-and-decisions.md).

No local LLM (`.litertlm`) is shipped: the external OpenCode Go provider is the
primary language-model path. The app-managed local-model catalog is intentionally
empty (`docs/local-models.md`).

The Smart Turn size and hash were read from the publisher's Hugging Face API and
independently re-verified when fetched (**2026-09-30**). The Kokoro identity was
re-verified with the WSL `curl` check above for M28. Smart Turn's description
and pinning rationale live in
[`docs/smart-turn.md`](../docs/smart-turn.md).

## Licenses

- **Smart Turn v3.2 ONNX** — BSD-2-Clause, per the publisher's `LICENSE`
  (1,406 bytes) in the model repo.
- **Distilled Kokoro `af_heart` graph** — Apache-2.0, per the pinned
  `MithrilMan/teacher-in-the-loop` model card and `LICENSE`.

Downloading, storing, and running these files is the user's responsibility; this
repository only records their identity and integrity. Do not commit the artifacts
or redistribute them from this repository.
