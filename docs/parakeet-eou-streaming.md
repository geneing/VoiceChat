# Parakeet-EOU streaming (removed)

**Status: removed in M27.** The app-managed Parakeet-EOU ONNX streaming STT
engine (its `stt/parakeet` package, ONNX runtime wiring, model store, install
flow, catalog mode, and diagnostics attributes) was deleted and replaced by the
standard Android speech-recognition API. See
[docs/android-speech-recognition.md](./android-speech-recognition.md) for the
current STT engine, its end-of-speech handling, and the file-replay and latency
harness, and `docs/decisions.md` §2.1/§5 for the decision record.

Removed with the engine: `scripts/push-parakeet-eou.ps1`,
`scripts/run-parakeet-eou-replay.ps1`, `scripts/run-parakeet-eou-voice-loop.ps1`,
`scripts/fetch-parakeet-candidate.ps1`, `scripts/push-parakeet-candidate.ps1`,
and `scripts/parakeet-eou-host.py`. The Litert/ASR evaluation scripts under
`scripts/litert_*` are a separate benchmark track and remain.

Historical risk entries that described the Parakeet engine (R-0236, R-0240,
R-0241) are marked closed in
[docs/risks-and-decisions.md](./risks-and-decisions.md).
