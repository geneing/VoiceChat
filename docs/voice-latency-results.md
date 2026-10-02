# Voice Latency and Accuracy Results

This is the single running record of measured progress. Each run records the
source commit whose code was tested. Raw audio remains on the phone; timing
reports contain file IDs and engine metadata only. Machine-generated transcript
drafts are separate, local, Git-ignored files and are not ground truth.

## Test setup

- Branch: `optimization/voice-latency`.
- Phone: Pixel 10 (`frankel`), Android 17 / API 37, build
  `google/frankel/frankel:17/CP3A.260905.009/16091614:user/release-keys`.
- Replay is a paced file feed; no microphone or speaker route participates.
  During the full corpus run the phone was at 80% battery, battery temperature
  28.4 C, and Android thermal status 0 (none); charging state was `not
  charging`. These readings were sampled during the run, not before it.
- Corpus present at start: ten root WAV files (16 kHz, mono, PCM16; about 79.7
  seconds total) and two `Walking*.m4a` outdoor recordings. None has a reviewed
  reference transcript. Do not calculate WER/CER until labels are reviewed.
- Initial ML Kit availability check: Advanced `Ready`; Basic
  `DownloadRequired` (2026-10-01). This is availability, not a transcription or
  latency result.

## Run log

| Date | Tested source commit | Change at checkpoint | Device / engine | Latency result | Accuracy result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-01 | `2edaf78017b802a83f24a8c4b804f151ca025cb6` (base; worktree was dirty) | Started the `optimization/voice-latency` work: app-scoped diagnostics wiring, paced WAV/M4A harness, and debug-only Moonshine Tiny/Small candidate were under development. | Pixel 10; ML Kit Advanced reported ready, Basic required download. | No corpus replay had been run. No latency claim. | No reviewed references or accuracy score. | The recorded source hash is the base commit; the source diff for this early status was uncommitted, so this row is status only and is not a reproducible benchmark. |
| 2026-10-01 | `ffd905b053156eba5788807574111df2771667aa` | Added app-scoped diagnostics, opt-in real-time WAV/M4A replay for ML Kit and Moonshine Tiny/Small, and separate local draft-label exports. JVM/Pixel smoke and formatting checks passed before the replay attempt. | Pixel 10, Android 17/API 37; ML Kit Advanced + Moonshine Tiny/Small. | No rows: both instrumentation runs stopped before decoding because the app-specific external-cache replay directory was missing. | No labels or accuracy result; the failed run wrote no sidecars. | Run attempted with `-Engine Both -Mode Advanced -ExportDraftLabels -ExportMoonshineDraftLabels`. Harness staging/installation order is being corrected. The app code tested was this commit; route, battery, and thermal conditions were not measured. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Changed replay staging to app-private cache via `run-as` and direct instrumentation so Android scoped storage and Gradle test installation do not hide staged files. | Pixel 10; ML Kit Advanced; fixture `eng-000129-002077dd.wav` (4,307 ms). | ML Kit failed after 627 ms / 5 frames, before input completed; `STT_RECOGNITION_FAILED`. No valid TTFS or finalization latency. | No final transcript and no reviewed reference; no score. | One-file harness smoke. The source in this checkpoint was exercised; model status had previously reported Advanced `Ready`. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Same private-cache harness checkpoint; Moonshine Voice debug candidate, cold first use. | Pixel 10; fixture `eng-000129-002077dd.wav` (4,307 ms); Tiny and Small streaming models. | Tiny: model open 17,441 ms; first partial 2,367 ms after audio start; final 215 ms after audio end; wall time 26,039 ms, RTF 6.046 including cold model setup. Small: model open 34,114 ms; first partial 2,371 ms; no final before timeout at 42,937 ms. | Tiny reached a final-result event, but the transcript was not exported and no reviewed reference exists; no WER/CER. Small timed out without a final result. | Single-clip cold run. Model open includes first-run model acquisition/setup; not a warm-inference comparison. Draft labels were not exported in this smoke. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Full-corpus ML Kit replay after the cache fix; 12 top-level files (10 WAV, two Walking M4A), each paced in 20 ms frames. | Pixel 10; ML Kit Advanced and Basic; 24 rows. | Advanced: 12/12 failed with `STT_RECOGNITION_FAILED`. Basic: 12/12 failed with `STT_MODEL_NOT_READY`. No valid TTFS, finalization, or RTF comparison. | No final hypotheses for review from these ML Kit attempts; no WER/CER. | Instrumentation completed successfully, but recognizer outcomes failed. Basic had reported `DownloadRequired` in the prior availability check. Moonshine full-corpus run was still in progress when this row was added. |

## Measurement definitions

- `input_start_to_first_partial_ms`: paced audio start to the first interim
  transcript. This is the primary streaming STT responsiveness measure.
- `input_end_to_final_ms`: end of paced audio to final transcript.
- `model_open_ms`: model load/download time reported separately where the
  engine exposes it. Keep cold setup separate from warm inference.
- `wall_time_ms` and real-time factor describe the entire replay operation;
  decoding/setup inclusion is noted with each run.
- Accuracy is reported as WER/CER and critical-meaning errors only against
  reviewed reference labels. Machine drafts remain explicitly unverified.

## Next run

Complete the running Moonshine corpus pass, then investigate why ML Kit Advanced
reports `Ready` but fails on paced replay and why Basic remains not ready. Repeat
successful engines after model setup is warm. Review the separate ignored draft
sidecars before calculating WER/CER; continue to record each run and source hash
in this file.
