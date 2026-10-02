# Voice Latency and Accuracy Results

This is the single running record of measured progress. Each run records the
source commit whose code was tested. The ML Kit, Moonshine, and Parakeet rows
below are historical results from optimization/voice-latency and do not measure
LiteRT. Append new LiteRT runs to this same file under a separate section.
Raw audio is never committed. The separately requested OpenAI reference step may upload only the user-owned Walking recordings; record its API time separately from device latency. Reports contain file IDs and engine metadata only.
Machine-generated transcript drafts are separate, Git-ignored files and are not
ground truth.

## Historical test setup (optimization/voice-latency)

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

## LiteRT benchmark branch status

- Branch: optimization/litert-speech-benchmark, created from clean master at source hash 2edaf78017b802a83f24a8c4b804f151ca025cb6.
- The historical setup, checkpoint table, and existing run log document the prior optimization/voice-latency branch and its sherpa ONNX Parakeet candidate, not LiteRT.
- No LiteRT conversion, backend execution, or device benchmark is recorded yet. Add only runs that were actually attempted; use explicit unavailable/failure outcomes for unsupported lanes.
## Significant checkpoints

| Commit | Change | Progress |
| --- | --- | --- |
| `ffd905b053156eba5788807574111df2771667aa` | Added app-scoped trace diagnostics, first LLM text timing after request construction, paced WAV/M4A STT replay, local draft sidecars, and debug-only Moonshine candidates. | Initial implementation checkpoint; replay staging issue recorded below. |
| `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Fixed replay staging through app-private cache and direct instrumentation. | One-file smoke passed; subsequent cold and full-corpus rows below. |
| `ab5f33c9a291584a8c75306f0ad0588f3dc42108` | Recorded initial Pixel ML Kit failures and added R-0235. | Interim measurement checkpoint; later full-corpus rows supersede its pending-run note. |
| `2ec3b2e71cab990b0b28e910302e2dd020c0f037` | Updated the plan, test instructions, and this log with the completed 12-file baseline results and model survey. | Documentation checkpoint; source results remain tied to their tested hashes in the run table. |
| `d652cdcfc3adcf763466e0af6c46278e57f205d9` | Changed Moonshine's debug update cadence to 100 ms for a controlled replay comparison. | Pixel replay completed; full measurements and outcome are below. |
| `711769bbc2afe9f9e44e74090c6de84d41f06baf` | Restored the 500 ms Moonshine default after the 100 ms comparison showed no material first-partial gain. | `:app:testDebugUnitTest` and `spotlessCheck` passed. The behavior matches the earlier 500 ms device baseline; no separate device pass was run. |
| `f4cb3d8979b453754bff18705766cb71529b06ab` | Consolidated run outcomes, hashes, and model progress into this single file; left `Tests.md` with replay instructions only. | Documentation checkpoint. |
| `0edf380` (`0edf380c138601ad4f92b7af3e390a80d8edb051`) | Added opt-in sherpa-onnx 1.13.8 / Parakeet TDT-CTC 110M offline replay, SHA-256-pinned local artifacts, debug-only AAR packaging, model staging, batch latency rows, and unverified draft-label export. | `:app:testDebugUnitTest`, `:app:assembleDebugAndroidTest`, and `spotlessCheck` passed. APK inspection found sherpa JNI and one app-pinned ONNX Runtime library per ABI. Pixel inference and accuracy review remain pending. |

## Run log

| Date | Tested source commit | Change at checkpoint | Device / engine | Latency result | Accuracy result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-01 | `2edaf78017b802a83f24a8c4b804f151ca025cb6` (base; worktree was dirty) | Started the `optimization/voice-latency` work: app-scoped diagnostics wiring, paced WAV/M4A harness, and debug-only Moonshine Tiny/Small candidate were under development. | Pixel 10; ML Kit Advanced reported ready, Basic required download. | No corpus replay had been run. No latency claim. | No reviewed references or accuracy score. | The recorded source hash is the base commit; the source diff for this early status was uncommitted, so this row is status only and is not a reproducible benchmark. |
| 2026-10-01 | `ffd905b053156eba5788807574111df2771667aa` | Added app-scoped diagnostics, opt-in real-time WAV/M4A replay for ML Kit and Moonshine Tiny/Small, and separate local draft-label exports. JVM/Pixel smoke and formatting checks passed before the replay attempt. | Pixel 10, Android 17/API 37; ML Kit Advanced + Moonshine Tiny/Small. | No rows: both instrumentation runs stopped before decoding because the app-specific external-cache replay directory was missing. | No labels or accuracy result; the failed run wrote no sidecars. | Run attempted with `-Engine Both -Mode Advanced -ExportDraftLabels -ExportMoonshineDraftLabels`. Harness staging/installation order is being corrected. The app code tested was this commit; route, battery, and thermal conditions were not measured. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Changed replay staging to app-private cache via `run-as` and direct instrumentation so Android scoped storage and Gradle test installation do not hide staged files. | Pixel 10; ML Kit Advanced; fixture `eng-000129-002077dd.wav` (4,307 ms). | ML Kit failed after 627 ms / 5 frames, before input completed; `STT_RECOGNITION_FAILED`. No valid TTFS or finalization latency. | No final transcript and no reviewed reference; no score. | One-file harness smoke. The source in this checkpoint was exercised; model status had previously reported Advanced `Ready`. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Same private-cache harness checkpoint; Moonshine Voice debug candidate, cold first use. | Pixel 10; fixture `eng-000129-002077dd.wav` (4,307 ms); Tiny and Small streaming models. | Tiny: model open 17,441 ms; first partial 2,367 ms after audio start; final 215 ms after audio end; wall time 26,039 ms, RTF 6.046 including cold model setup. Small: model open 34,114 ms; first partial 2,371 ms; no final before timeout at 42,937 ms. | Tiny reached a final-result event, but the transcript was not exported and no reviewed reference exists; no WER/CER. Small timed out without a final result. | Single-clip cold run. Model open includes first-run model acquisition/setup; not a warm-inference comparison. Draft labels were not exported in this smoke. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Full-corpus ML Kit replay after the cache fix; 12 top-level files (10 WAV, two Walking M4A), each paced in 20 ms frames. | Pixel 10; ML Kit Advanced and Basic; 24 rows. | Advanced: 12/12 failed with `STT_RECOGNITION_FAILED`. Basic: 12/12 failed with `STT_MODEL_NOT_READY`. No valid TTFS, finalization, or RTF comparison. | No final hypotheses for review from these ML Kit attempts; no WER/CER. | Instrumentation completed successfully, but recognizer outcomes failed. Basic had reported `DownloadRequired` in the prior availability check. |
| 2026-10-01 | `acdce6ef505ee2b60745d88daa95939ef7ff0358` | Full-corpus Moonshine streaming STT pass; warmed Tiny/Small models after a separate cold-first-use smoke. | Pixel 10; Moonshine Voice 0.1.5; Tiny Streaming and Small Streaming; 12 source files/model, all paced at 20 ms. | Tiny: 12/12 `FINAL`; first partial p50 2,184.5 ms / p90 7,750.9 ms; end-to-final p50 59.0 ms / p90 322.4 ms; RTF p50 1.823 / p90 2.020. Small: 12/12 `FINAL`; first partial p50 2,276.5 ms / p90 7,878.0 ms; end-to-final p50 74.0 ms / p90 1,063.8 ms; RTF p50 2.022 / p90 2.094. | Draft sidecars exported locally for both engines (24 rows each) and verified Git-ignored. No human review yet; no WER/CER. | Tested code hash above. Percentiles use linear interpolation at `(n-1)*p`; 12 cases/model, so p90 is descriptive and sensitive to the two outdoor Walking clips. Their first-partial latencies were about 9.78/8.36 s for Tiny and 9.99/8.49 s for Small. Model-open time was 584 ms for the first Tiny case and 1,114 ms for the first Small case, then 0–4 ms; cold first-use smoke separately measured 17.441 s/34.114 s including model acquisition/setup. Reports are local temp CSVs; no transcript text copied here. |
| 2026-10-02 | `d652cdcfc3adcf763466e0af6c46278e57f205d9` | Changed Moonshine's debug candidate update interval from 500 ms to 100 ms; reran the full corpus. | Pixel 10; Moonshine Voice 0.1.5; Tiny and Small Streaming; 12 source files/model, paced at 20 ms. Device was USB-powered/charging at 80%, battery 28.6 °C during and 29.2 °C after the run; thermal status remained 0. Instrumentation took 505.681 s. | Tiny: 12/12 `FINAL`; first partial p50 2,168.5 ms / p90 7,738.6 ms; end-to-final p50 32 ms / p90 314.5 ms; RTF p50 1.83 / p90 1.98. Small: 12/12 `FINAL`; first partial p50 2,310 ms / p90 7,819.4 ms; end-to-final p50 69 ms / p90 466.3 ms; RTF p50 2.05 / p90 2.17. | No reviewed reference; no WER/CER. For diagnostic consistency only, exact draft-output agreement with the earlier 500 ms run was 7/12 Tiny and 8/12 Small; this is not an accuracy score. New Moonshine draft sidecar is local and Git-ignored. | Relative to the 500 ms run, Tiny first-partial p50 improved only 16 ms and p90 12 ms; Small p50 regressed 33.5 ms while p90 improved 58.6 ms. The more frequent updates did not make first speech meaningfully faster and changed several hypotheses. Use 500 ms for the next comparison. Run report is local temp CSV `voicechat-moonshine-100ms-d652cdc.csv`; no transcript text copied here. |
| 2026-10-02 | `711769bbc2afe9f9e44e74090c6de84d41f06baf` | Repeated the 500 ms Tiny/Small corpus pass after restoring the default. | Pixel 10; Moonshine Voice 0.1.5; 12 source files/model; USB-powered/charging at 80%, battery 29.2 °C after run, thermal status 0. Instrumentation took 503.331 s. | Tiny: 12/12 `FINAL`; first partial p50 2,183.5 ms / p90 7,773.4 ms; end-to-final p50 55 ms / p90 388.5 ms; RTF p50 1.8 / p90 2.0. Small: 12/12 `FINAL`; first partial p50 2,228.5 ms / p90 7,773.3 ms; end-to-final p50 40.5 ms / p90 573.2 ms; RTF p50 2.0 / p90 2.2. | No draft sidecar or reviewed reference in this repeat; no WER/CER. | First-partial p50 repeated within 34.5 ms per clip for Tiny and 53 ms for Small (median absolute difference versus the first 500 ms pass); max per-clip difference was 135 ms Tiny and 244 ms Small. This supports similar medians but p90 is sensitive to outdoor clips. Report is local temp CSV `voicechat-moonshine-repeat-500ms-711769b.csv`; no transcript text copied here. |
| 2026-10-02 | source `0edf380ed1bcfea29eb17b1abc3425c15733938e` (HEAD `cdb7d6674a73bfae254f12f4e5f37242ba538c5a`, docs-only after source) | First Pixel 10 full-corpus run of optional offline Parakeet 110M int8 through sherpa-onnx; added its Android replay harness and verified model/runtime packaging. | Pixel 10, Android 17/API 37; Parakeet NeMo 110M int8, sherpa-onnx 1.13.8. 12 files; USB-powered/charging, 80%, 27.7 °C before and after, thermal status 0. | 12/12 final. Shared model initialization 1,153 ms. Audio decode p50 3 ms / p90 1,690.2 ms; inference p50 235 ms / p90 512 ms; decode-plus-inference wall time p50 321 ms / p90 2,300.4 ms. Full-phrase real-time factor p50 0.040 / p90 0.094; inference-only RTF p50 0.026 / p90 0.038. | No partial streaming is exposed by this offline recognizer, so first-partial latency is unavailable. Draft labels exported locally for review; no reviewed references or WER/CER. | Output `voicechat-parakeet-0edf380.csv` in local temp. M4A audio decoding drives the high p90 wall time; do not compare that metric directly to streaming first-partial latency. |

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
- Percentiles use linear interpolation at `(n-1)*p`; sample count is reported
  with each run. Small corpora are descriptive rather than a stable population
  estimate.

## Draft labels and review status

The user requested local draft labels for review. The run wrote two ignored
sidecars under `test_data/` (24 rows each, one result row per model and source
file). Both were checked with Git ignore rules. They contain unverified model
outputs and are not ground truth; no text was copied into tracked documentation.
Review/edits are still required before measuring WER/CER.

## Next run

The 100 ms Moonshine update interval did not improve first-partial latency enough
to justify the changed hypotheses and extra callbacks; the debug candidate is
restored to 500 ms. The first Parakeet offline run completed all 12 files, but
has no native partial timing; its p50 inference was 235 ms and p90 was 512 ms.
Next repeat the full Parakeet corpus to check timing stability and continue
reviewing its locally exported draft labels. Investigate ML Kit Advanced failure
and Basic model availability. Do not move to TTS latency optimization until a
streaming STT candidate has materially lower first-partial latency without an
unacceptable accuracy or resource regression. Continue to record each run's
tested source hash and concise change description here.

## LiteRT run record (optimization/litert-speech-benchmark)

No LiteRT conversion or device benchmark has been run on this branch yet. Add only
real attempted runs below, with the exact tested source hash and backend evidence.

| Date | Source commit | Model / quantization | Device / requested backend | Confirmed backend / outcome | Latency / accuracy | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| No run recorded | | | | | | |

## LiteRT setup checkpoints

| Date | Source commit | Change description | Environment / outcome | Timing and accuracy |
| --- | --- | --- | --- | --- |
| 2026-10-02 | dd857417a2ee1894cfdc8e0937858a9378293367 | Pinned the LiteRT speech sample source revision and added a Linux/WSL isolated conversion environment with a CPU-only PyTorch wheel. | WSL2 Ubuntu, CPython 3.12.3. Pinned sample source: 07d62e59e235274425863262918eb8794482662c. Shell syntax and source checkout checks passed. Pinned package installation is still running; no ASR model weights were requested. Android preflight found zero connected devices. OPENAI_API_KEY was absent, so no Walking audio was uploaded. | No model conversion, device inference, timing, or accuracy measurement yet. |
| 2026-10-02 | 95ff477b7a4ddcf8267b1762e7d840b9bc2dfd3a | Added guarded wrappers for the sample-listed converter models and a separate Whisper Small extension; documented WSL-native venv override and the sample’s non-FP16/non-static-INT8 quantization limits. | WSL2 Ubuntu, CPython 3.12.3; pinned sample source checkout matches 07d62e59e235274425863262918eb8794482662c. Shell syntax and wrapper help passed. `pip check` reported no broken requirements, but the first converter CLI invocation failed at import with `ModuleNotFoundError: No module named 'absl'`; setup was incomplete and is being retried. `adb devices -l` listed zero devices. | No model conversion, device inference, latency, or accuracy result. No API request or audio upload. |
