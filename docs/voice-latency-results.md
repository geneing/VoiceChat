# Voice Latency and Accuracy Results

This is the single running record of measured progress. Each run records the
source commit whose code was tested. Raw audio remains on the phone; timing
reports contain file IDs and engine metadata only. Machine-generated transcript
drafts are separate, local, Git-ignored files and are not ground truth.

## Test setup

- Branch: `optimization/voice-latency`.
- Phone: Pixel 10 (`frankel`), Android 17 / API 37. The exact build fingerprint,
  audio route, battery state, and thermal state will be recorded for each device
  run when captured.
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

Run the same paced corpus on ML Kit Advanced and Moonshine Tiny/Small on the
Pixel 10, capture repeated runs and device conditions, then append aggregate and
per-engine outcomes with the exact tested source commit. Generate local draft
labels only with the explicit opt-in and keep them outside Git.
