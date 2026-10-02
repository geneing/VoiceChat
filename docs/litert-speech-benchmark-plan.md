# LiteRT Speech Recognition Benchmark Plan

**Branch:** optimization/litert-speech-benchmark
**Planning base:** 2edaf78017b802a83f24a8c4b804f151ca025cb6
**Plan created:** 2026-10-02
**Status:** CPU-only Moonshine Tiny conversion and host signature-load smoke checks are recorded below. A debug/test-only Android CPU instrumentation spike now compiles, but no Android inference/device benchmark has run. The single-window spike has no transcript or accuracy output.

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
unavailable combinations with the exact missing component. The current preflight
saw zero ADB devices, so no phone lane is presently runnable.

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
