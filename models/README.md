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

The Parakeet candidate uses a separate, pinned Windows PowerShell fetcher because
its optional Android AAR is a debug-evaluation dependency:

```powershell
.\scripts\fetch-parakeet-candidate.ps1
```

This downloads the sherpa-onnx 1.13.8 Android AAR plus the Parakeet TDT-CTC
110M int8 ONNX weights and tokens. The model is 132 MB and stays outside both
app APKs; `scripts/push-parakeet-candidate.ps1` installs it under app-private
storage for on-device replay. The AAR is added to debug builds only when present
locally, and the candidate instrumented source set is omitted from clean
checkouts. Check local artifacts against `manifest.json` before use. The ONNX
Runtime shared-library compatibility risk is tracked in
[`docs/risks-and-decisions.md`](../docs/risks-and-decisions.md).

Install them into the app's **private** storage on a device with:

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
| `parakeet-110m-int8` | `parakeet-tdt-ctc-110m-en-int8/model.int8.onnx` | 131,652,171 | `9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a` | CC-BY-4.0 | `VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8@43869f7` |
| `parakeet-110m-tokens` | `parakeet-tdt-ctc-110m-en-int8/tokens.txt` | 9,953 | `450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10` | CC-BY-4.0 | `VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8@43869f7` |
| `sherpa-onnx-android-1.13.8` | `sherpa-onnx-1.13.8.aar` | see release | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` | Apache-2.0 | `k2-fsa/sherpa-onnx@v1.13.8` |

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
- **NVIDIA Parakeet TDT-CTC 110M** — CC-BY-4.0. The selected int8 model is
  quantized by VocaHQ from the pinned sherpa-onnx upstream export; preserve
  NVIDIA model and VocaHQ conversion attribution when sharing derived results
  or artifacts.
- **sherpa-onnx Android runtime** — Apache-2.0, pinned to v1.13.8 for candidate
  evaluation only.

Downloading, storing, and running these files is the user's responsibility; this
repository only records their identity and integrity. Do not commit the artifacts
or redistribute them from this repository.

