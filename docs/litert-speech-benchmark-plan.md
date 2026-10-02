# LiteRT Speech Recognition Benchmark Plan

**Branch:** optimization/litert-speech-benchmark
**Planning base:** 2edaf78017b802a83f24a8c4b804f151ca025cb6
**Plan created:** 2026-10-02
**Status:** Harness scaffolding is in progress. This branch has not run a LiteRT conversion or device benchmark.

## Objective

Build a repeatable Android benchmark around Google's LiteRT speech-recognition sample for Parakeet TDT, Parakeet CTC, Moonshine, and Whisper. Compare CPU, GPU, and NPU only for combinations that the sample and selected device support. Report both the requested backend and evidence of the backend that actually executed the model. Keep the product's ML Kit STT adapter in place while LiteRT remains an evaluation candidate.

All measured results belong in the single running record at [voice-latency-results.md](./voice-latency-results.md). Each measurement entry must identify the exact code commit, model/export and quantization checksums, device/build, runtime/toolchain versions, requested and confirmed backend, sample set, warm/cold state, and failure counts. No unsupported or unmeasured value is zero.

## Source behavior to preserve

The upstream sample is the reference for preprocessing and decoding, not merely a model-file source:

- It processes file audio in 5-second windows with 2-second overlap; its live microphone path uses 5-second windows with 4-second overlap and initial zero padding. Its models are not native streaming models. Early hypotheses from overlapping windows must be labeled simulated incremental-window output, not native streaming.
- Moonshine receives raw waveform samples. Parakeet and Whisper use log-Mel features. Per-model sample rate, feature configuration, tensor signatures, padding/truncation, special token IDs, decode strategy, timestamp handling, and overlap-merge behavior must come from the corresponding sample metadata and decoder, not be guessed or shared across model families.
- Parakeet TDT has stateful decoding; Parakeet CTC and the sample's Moonshine/Whisper paths use their own decoder contracts. Preserve the sample's encoder/decoder tensor ordering and zero-copy buffers where the model graph supports them.
- The sample asks LiteRT to use both CPU and the requested accelerator, so the request alone cannot establish placement. A result is CONFIRMED only with runtime evidence. If evidence is unavailable, record UNKNOWN; if the runtime falls back to CPU or delegates only part of the graph, record that explicitly.

Reference: [Google AI Edge speech-recognition sample](https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition), checked 2026-10-02.

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

### M1 — Benchmark contracts, runner, and diagnostics

Create a test-only benchmark schema for model family, artifact variant, requested backend, confirmed backend/evidence, per-stage duration, outcome, and failure detail. Add a host-side runner that validates model/backend combinations, stages only ignored local model artifacts and the test corpus into app-private device storage, invokes the benchmark instrumentation, and collects a content-free CSV. Do not put transcript text, raw audio, keys, or full provider responses in logs.

Use explicit states — NOT_IMPLEMENTED, UNAVAILABLE, LOAD_FAILED, RUNTIME_FALLBACK, PARTIAL_DELEGATION, CONFIRMED — so the harness cannot report a missing lane as a successful result. Keep the existing ML Kit production implementation and the recorded historical rows. The clean master baseline contains ML Kit availability/refusal instrumentation but no device file-replay harness; do not resurrect that old harness. The new LiteRT evaluation path will be the only file-based real-engine benchmark; retain the ML Kit runtime-availability and refusal tests.

### M2 — Sample-compatible model pipeline

Implement reusable audio decoding and the sample's per-family preprocessing and decoder contracts. Capture cold load, warm-up, preprocess, encoder, decoder, postprocess/window merge, first window-hypothesis, finalization, and total wall time. Separate one-shot throughput from overlapping-window incremental-output timing. Record input audio duration and real-time factor. Validate determinism and transcript-free logging with host tests.

### M3 — Conversion and quantization

Reproduce the supported FP32 recipes; add the separate Whisper Small recipe; create documented FP16 and calibrated static INT8 post-quantization wrappers where tool support permits. Record source hashes, local artifact checksums, calibration identity, output sizes, and parity checks. If a model or quantization recipe fails, record the failed attempt and preserve the prior known-good artifact.

### M4 — Pixel 10 device matrix

Run CPU/GPU/NPU lanes only where device, sample artifact, runtime package, and model support align. The Pixel 10 is the primary device. Before timed runs, record OS/build, SoC, runtime/delegate package versions, model hash, quantization, device thermals/power state, and requested accelerator. Run model warm-up separately from repeated measured clips. Confirm actual accelerator execution; report unsupported combinations as UNAVAILABLE, not CPU fallback success.

### M5 — Accuracy reference and quality review

For the user-owned test_data/Walking* outdoor recordings only, use the OpenAI audio transcription endpoint with gpt-4o-transcribe as the default high-quality reference hypothesis. The user explicitly requested this accuracy benchmark. Upload only those Walking recordings; do not send the other fixtures until their provenance and redistribution/transfer terms permit it. Preserve each returned transcript in a local Git-ignored review sidecar, mark it as a pseudo-reference, and require human review before WER/CER or critical-error scoring. Do not log audio or transcript content.

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
