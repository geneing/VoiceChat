# LiteRT Speech Recognition Benchmark Plan

**Branch:** optimization/litert-speech-benchmark (merged into optimization/voice-latency at fc35370)
**Planning base:** 2edaf78017b802a83f24a8c4b804f151ca025cb6
**Plan created:** 2026-10-02
**Status:** Moonshine Tiny FP32 runs on the Pixel 10 CPU (four cold single-window spikes; see results). Whisper Tiny and Small FP32-30s run on the Pixel 10 CPU with host-extracted log-Mel features and a decode bounded to 16 steps (model-graph timings only; no transcript); the experimental Whisper device spike was then removed (results preserved), and only the conversion tooling remains. Parakeet CTC 0.6B FP32 converted through a tracked compatibility shim and reduced to an FP16-weight artifact (1.18 GB, 50.4% of FP32) and a partial INT8 artifact (0.60 GB, 25.6%, 39.5% internal INT8 coverage — not a full-INT8 candidate). The official litert-community Parakeet INT8 artifacts were then run on the Pixel 10 CPU for both CTC and TDT after fixing the TDT decoder stride: TDT completes (31 iterations / 23 tokens, encode 345 ms + decode 335 ms) versus CTC (63 iterations / 18 tokens, encode 331 ms + decode 28 ms). The GPU lane fails for both Moonshine and Parakeet under LiteRT 2.1.5 — the OpenGL (ML Drift) delegate cannot compile the graphs, so there is no GPU execution. The Tensor G5 NPU lane is UNAVAILABLE (R-0238): the v2.1.1 dispatch library does not register with the pinned runtime, so the upstream F32 AOT G5 model fails with an unresolved `DISPATCH_OP`. **Accuracy:** the sample tokenizer JNI is now linked and the full 12-clip corpus runs on-device. Parakeet CTC scores WER 0.00-0.80 on the clips that fit its 5 s window against a local Whisper pseudo-reference. R-0239 is resolved: the corrected pure-Kotlin TDT front end matches the frozen TDT feature file on Pixel 10 with max/mean abs `0.0002205372 / 0.0000006845`; the TDT corpus produces text for 9/10 clips, including all four clips that fit its 5 s window. In-window TDT mean WER/CER is 0.186/0.150 against the same unreviewed local Whisper pseudo-reference, not human gold. The TDT *conversion* path is still not exercised because `nemo_toolkit` is absent. No device GPU/NPU timing exists.

## Objective

Build a repeatable Android benchmark around Google's LiteRT speech-recognition sample for Parakeet TDT, Parakeet CTC, Moonshine, and Whisper. Compare CPU, GPU, and NPU only for combinations that the sample and selected device support. Report both the requested backend and evidence of the backend that actually executed the model. Keep the product's ML Kit STT adapter in place while LiteRT remains an evaluation candidate.

All measured results belong in the single running record at [voice-latency-results.md](./voice-latency-results.md). Each measurement entry must identify the exact code commit, model/export and quantization checksums, device/build, runtime/toolchain versions, requested and confirmed backend, sample set, warm/cold state, and failure counts. No unsupported or unmeasured value is zero.

## Source behavior to preserve

The upstream sample is the reference for preprocessing and decoding, not merely a model-file source:

- It processes file audio in 5-second windows with 2-second overlap; its live microphone path uses 5-second windows with 4-second overlap and initial zero padding. Its models are not native streaming models. Early hypotheses from overlapping windows must be labeled simulated incremental-window output, not native streaming.
- Moonshine receives raw waveform samples. Parakeet and Whisper use log-Mel features. Per-model sample rate, feature configuration, tensor signatures, padding/truncation, special token IDs, decode strategy, timestamp handling, and overlap-merge behavior must come from the corresponding sample metadata and decoder, not be guessed or shared across model families.
- Parakeet TDT has stateful decoding; Parakeet CTC and the sample's Moonshine/Whisper paths use their own decoder contracts. Preserve the sample's encoder/decoder tensor ordering and zero-copy buffers where the model graph supports them.
- The sample asks LiteRT to use both CPU and the requested accelerator, so the request alone cannot establish placement. A result is CONFIRMED only with runtime evidence. If evidence is unavailable, record UNKNOWN; if the runtime falls back to CPU or delegates only part of the graph, record that explicitly.

Pinned conversion source: google-ai-edge/litert-samples commit
07d62e59e235274425863262918eb8794482662c. The conversion environment uses
CPython 3.12 on Linux/WSL, with direct pins in
[scripts/litert-asr-conversion-requirements.txt](../scripts/litert-asr-conversion-requirements.txt)
and an isolated venv under ignored build/ by default. Set LITERT_ASR_VENV_DIR to use WSL native storage when installing on a mounted Windows worktree. LiteRT Torch is Linux-only; do not
install these packages into the app's Windows/Gradle environment. CPU-only
PyTorch is pinned from the official download.pytorch.org/whl/cpu index. A full
resolved dependency lock and wheel hashes are pending the first successful
isolated install.

The sample converter lists Whisper Tiny, not Whisper Small. Whisper Small is a
separate extension candidate that must be converted and validated independently.
The sample conversion CLI supports none and DRQ only; neither is FP16 or
static INT8. Use ai-edge-quantizer FLOAT_CASTING for an FP16 weight artifact
(compute can remain FP32), and a separate calibrated static_wi8_ai8 recipe for
an INT8 weights-and-activations NPU candidate. Follow the official
[quantization recipe guide](https://github.com/google-ai-edge/litert-samples/blob/main/skills/accuracy-safe-quantization/SKILL.md)
and [ai-edge-quantizer API](https://github.com/google-ai-edge/ai-edge-quantizer).
Reference: [Google AI Edge speech-recognition sample](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition), checked 2026-10-02.

## Runtime constraints and evidence gates

Start with CPU/GPU. The sample Android app comments out its NPU runtime
libraries and requires accelerator-specific packaging. LiteRT issue
[#8474](https://github.com/google-ai-edge/LiteRT/issues/8474) reports the
litert 2.1.5 artifact avoids a duplicate namespace build failure reported for
2.1.6; verify the dependency on this app before wiring it. LiteRT issue
[#7787](https://github.com/google-ai-edge/LiteRT/issues/7787) is a historical,
closed report describing Pixel Tensor G5 NPU runs that fell back to CPU-like
timing or crashed under a then-tested runtime/dispatcher/AOT combination. The
visible issue text does not establish a current resolution, so it is not proof
that no current pairing exists. Attempt NPU only when a current Tensor runtime,
dispatcher, dynamic-feature/AiPack packaging, and artifact are available; mark
unavailable combinations with the exact missing component. An earlier preflight saw zero ADB devices; the Pixel 10 (`frankel`) is now attached (`57220DLCR002R6`), so the CPU lane is runnable and has been run. The GPU/NPU lanes remain gated on actual delegate placement evidence.

The upstream `LiteRtRunner` normally allows both CPU and the selected
accelerator, so its default GPU/NPU candidate path can fall back to CPU. The
current spike deliberately passes a CPU-only allowlist and disables fallback;
future GPU/NPU measurements must report placement evidence or remain
`UNKNOWN`. Upstream model metadata distinguishes the general Parakeet TDT
`_i8_stateful.tflite` URL from its Pixel-targeted NPU
`_f32_stateful_<target>.tflite` URL. That F32 target artifact is not evidence
for the requested INT8 NPU lane. Do not label it INT8 or treat a successful
fallback as accelerator execution.
## Model and backend matrix to evaluate

The upstream README lists these broad lanes. They are compatibility claims from the sample, not results from this project:

| Model family | CPU | GPU | NPU target listed by sample | Additional scope |
| --- | --- | --- | --- | --- |
| Parakeet TDT | Candidate | Candidate | Pixel 10 | Establish FP32 baseline, then GPU FP16 and a separately calibrated NPU INT8 weights-and-activations candidate. |
| Parakeet CTC | Candidate | Candidate | Galaxy S23/24 | NPU is unavailable on Pixel 10 unless the sample adds a verified compatible artifact/runtime. |
| Moonshine | Candidate | Candidate | None listed | CPU/GPU only in this matrix. |
| Whisper Tiny | Candidate | Candidate | None listed | This is the sample conversion recipe baseline. |
| Whisper Small | Candidate | Candidate | None listed | Implement and validate a separate conversion benchmark; the sample recipe does not currently list Small. |

Every Candidate cell remains unavailable until conversion succeeds, the app builds, the target device runs the graph, and backend evidence is recorded. Do not infer NPU support for Moonshine or Whisper. Do not treat a Pixel 10 TDT artifact whose filename says F32 as an INT8 artifact.

## Quantization and conversion plan

1. Pin the upstream sample revision and Python/runtime tool versions used. Record model repository revision, license, exporter, model config, and source hashes before conversion.
2. Reproduce each model's sample FP32 conversion and verification first. The ASR converter currently exposes none and dynamic-range quantization (DRQ); DRQ is not FP16 and does not mean INT8 activations.
3. Add a separate FP16 post-conversion script using the sample's documented ai-edge-quantizer FLOAT_CASTING recipe. Verify artifact-size change and model output/task parity against FP32.
4. Add a separately calibrated static INT8 weights-and-activations path for eligible NPU artifacts. Pin ai-edge-quantizer, calibration format/data source, representative sample count, and every option. The AI Edge Quantizer repository provides recipe.static_wi8_ai8() and Quantizer.calibrate() for full-integer weights-and-activations quantization; calibration data must match the model signature and input tensor names. The accuracy-safe quantization guide identifies this as a separate NPU/AOT lane requiring calibration. Implement the wrapper against the pinned quantizer API, then verify operator coverage for each ASR graph and NPU compiler support before claiming a usable artifact. No INT8 claim is valid from weights-only or DRQ quantization.
5. For each transformed model, repeat host parity checks and a target-device load/inference gate. Keep FP32, FP16, and INT8 files separate and Git-ignored. Export checksums and a small tracked manifest; never package large weights in the APK by default.
6. Treat Whisper Small as its own conversion and compatibility milestone; validate its encoder/decoder signatures, memory, decode length, and parity independently from Tiny.

#### Quantization wrapper status (2026-10-02)

Added `scripts/quantize-litert-asr-artifact.sh` and
`scripts/quantize_litert_asr_artifact.py`. The wrapper enforces the pinned
sample revision, accepts only source/output artifacts under ignored `models/`,
and keeps the FP32 source intact. `fp16` uses AI Edge Quantizer's
`FLOAT_CASTING` weight-only recipe: FP16 constants are explicitly
dequantized, so the arithmetic remains FP32. This is an FP16-weight artifact
for later GPU evaluation, not evidence of FP16 GPU execution. `int8` uses
`static_wi8_ai8()` and builds per-sample calibration dictionaries by calling
the pinned sample's audio processor, encoder, and decoder input builder. It
checks exact names, dtypes, and shapes for every exported signature, including
`decode_1` when present for stateful TDT. Its graph audit reports integer
versus floating-point internal operator outputs and refuses to label partial
graphs as full INT8 candidates. Named signatures are compared with CPU
inference and explicit public I/O quantization; this is a numeric graph check,
not a transcript accuracy score.

The preparation helper
`scripts/prepare-litert-asr-calibration.sh` makes private temporary 16 kHz
mono PCM copies of only the user-described `Walking*.m4a` recordings under
`/tmp/voicechat-litert-asr-calibration/`; it does not alter the originals or
send audio to a service. The two local WAV fixtures previously tried for a
calibration probe are not used as final evidence: the repo has no manifest for
those top-level files, and R-0037 leaves their exact provenance/license/
consent unverified. The final calibration attempt uses two user-recorded
Walking clips with source and derivative hashes saved in the ignored local
quantization sidecar. No license or redistribution right is claimed. No
device placement is established by these host-side transformations.

For backend claims, treat these outputs conservatively. The FP16 output contains
half-precision constants but runs FP32 operations behind dequantization; no GPU
execution or GPU speedup is established. The Moonshine static INT8 output has
floating-point operator outputs remaining and is marked partial, not a
full-INT8 NPU artifact. The pinned sample README lists Moonshine CPU/GPU only.
Although its metadata contains an NPU URL pattern, no compatible AOT artifact
or Tensor runtime was verified for this output. The sample's NPU path also uses
device-specific runtime packaging and a separate model file; requesting the
NPU accelerator alone would not confirm execution.

References: [sample converter](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/speech_recognition/convert/convert_to_tflite.py), [accuracy-safe quantization guidance](https://github.com/google-ai-edge/litert-samples/blob/main/skills/accuracy-safe-quantization/SKILL.md), [AI Edge Quantizer README](https://github.com/google-ai-edge/ai-edge-quantizer), [static recipe](https://github.com/google-ai-edge/ai-edge-quantizer/blob/main/ai_edge_quantizer/recipe.py), and [calibration example](https://github.com/google-ai-edge/ai-edge-quantizer/blob/main/ai_edge_quantizer/examples/mnist/quantize_toy_model.py).

## Harness and instrumentation milestones

### M0 — Isolated branch and evidence log (complete)

Created this branch from clean master at 2edaf78017b802a83f24a8c4b804f151ca025cb6. Copied the established ML Kit/Moonshine/Parakeet run history into the one results file without rewriting its measurements. The historical setup and rows are labeled as evidence from optimization/voice-latency; the LiteRT run table is separate. The production MlKitSpeechToText runtime is unchanged.

### M1a — Content-free contract and preflight (complete)

Added model/backend lane classification, requested-versus-observed backend
fields, guarded placement evidence, run metadata, and a content-free
preflight-only runner. Focused JVM tests and formatting passed in commit
89fc9ed33c5a4f874696960509037f58332068c5. The runner does not copy audio or
models, invoke LiteRT, or create timing results. The clean master baseline
contains ML Kit availability/refusal instrumentation but no device file-replay
harness; production MlKitSpeechToText, mic smoke test, and generic replay
contracts remain intact.

### M1b — CPU instrumentation spike (scaffold complete; device run pending)

Added a debug-test-only Moonshine Tiny CPU path using the pinned sample's
LiteRT `CompiledModel` encode/decode signatures, default autoregressive token
decoder, and RIFF/PCM16 window reader. The test accepts only the verified
5-second FP32 artifact and records cold model setup, WAV read/preprocessing,
encoder, first decoded token, decoder total, model/audio hashes, device details,
and thermal/power metadata. Its allowlist is CPU-only, so a completed encode is
CPU evidence with fallback disabled; earlier failures remain `UNKNOWN`/failed.
Production ML Kit remains unchanged.

This first spike is a single offline window. It does not pace input at 1x,
perform overlap merging, load the sample's tokenizer JNI library, produce a
transcript, or measure accuracy / clip-start first text. Those values are
explicitly unavailable; only the instrument-stage timings are meaningful. The
sample tokenizer target depends on an NDK-built `tokenizer_jni` library linked
against `libtokenizers_c.a`; it is not part of this test-only runtime. GPU,
verified text output, repeated warm runs, and real-time paced replay remain
future M1b/M2 work. No device timing is claimed until the staged test actually
runs on a connected device.

To unblock Android test compilation, this checkpoint also adds an explicit
`SelectedVoiceUnavailable` result branch to the existing M11 TTS instrumentation
probe. It only logs the result in a test and does not change production TTS; it
is unrelated to the LiteRT harness.
### M2 — Sample-compatible model pipeline

Implement reusable audio decoding and the sample's per-family preprocessing and decoder contracts. Capture cold load, warm-up, preprocess, encoder, decoder, postprocess/window merge, first window-hypothesis, finalization, and total wall time. Separate one-shot throughput from overlapping-window incremental-output timing. Record input audio duration and real-time factor. Validate determinism and transcript-free logging with host tests.

### M3 — Conversion and quantization

Reproduce the supported FP32 recipes; add the separate Whisper Small recipe; create documented FP16 and calibrated static INT8 post-quantization wrappers where tool support permits. Record source hashes, local artifact checksums, calibration identity, output sizes, and parity checks. If a model or quantization recipe fails, record the failed attempt and preserve the prior known-good artifact.

### M4 — Pixel 10 device matrix

Run CPU/GPU/NPU lanes only where device, sample artifact, runtime package, and model support align. The Pixel 10 is the primary device. Before timed runs, record OS/build, SoC, runtime/delegate package versions, model hash, quantization, device thermals/power state, and requested accelerator. Run model warm-up separately from repeated measured clips. Confirm actual accelerator execution; report unsupported combinations as UNAVAILABLE, not CPU fallback success.

### M5 — Accuracy reference and quality review

For the user-owned test_data/Walking* outdoor recordings only, use the OpenAI audio transcription endpoint with gpt-4o-transcribe as the default high-quality reference hypothesis. The user explicitly requested this accuracy benchmark. Upload only those Walking recordings; do not send the other fixtures until their provenance and redistribution/transfer terms permit it. Preserve each returned transcript in a local Git-ignored review sidecar, mark it as a pseudo-reference, and allow provisional WER/CER only when labeled unreviewed pseudo-reference agreement; this is not gold accuracy. Human-edited labels produce reviewed WER/CER and critical-error scoring. Do not log audio or transcript content.

Record request duration and API result separately from every on-device inference and latency result. Network wait, upload, and provider processing time are not STT device latency. Keep the reference generation reproducible by recording model ID, request settings, file ID/hash, timestamp, and outcome without copying transcript text into tracked logs. The API output is a reviewable hypothesis, not unquestionable ground truth.

Reference: [OpenAI audio transcription API](https://platform.openai.com/docs/api-reference/audio/createTranscription).

### M6 — Decision and product integration

Compare quality, first-window latency, final latency, throughput, memory, startup cost, thermal behavior, and actual backend placement. Keep the LiteRT path debug/evaluation-only until conversion parity, device evidence, licensing, memory/storage requirements, and failure handling are reviewed. A separate product decision is required before replacing the production ML Kit runtime.

## First milestone acceptance

- This plan and imported historical log are committed before benchmark code.
- Harness schemas distinguish request from confirmed backend placement and represent unavailable/fallback/error outcomes.
- The runner cannot silently treat fallback execution as a requested accelerator pass.
- The implementation leaves production ML Kit STT and all historical ML Kit/Moonshine/Parakeet records intact.
- No model files, raw speech, draft transcript text, API credentials, or benchmark claims are added to Git.
- Host tests/build checks are run and documented; no device or conversion result is claimed before actually running that lane.
