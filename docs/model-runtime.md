# Model and Runtime Support

This document defines the intended selection policy and the narrow app-managed
model allow-list. The app currently uses the pinned Smart Turn v3.2 ONNX graph
and the distilled Kokoro `af_heart` ONNX TTS graph. This does not permit arbitrary
ONNX files or model URLs. Runtime versions and artifact identities are recorded
in the [decision record](./decisions.md).

## Runtime categories

### AICore and ML Kit GenAI

Use supported Android / ML Kit GenAI APIs for eligible on-device features.
Discover feature and model availability at runtime and provide a clear
unavailable state when the device, OS, account, or model provisioning does not
meet requirements. AICore-backed availability can vary by device and rollout.

Treat AICore-managed models as system-managed. Do not download, copy, inspect,
or delete their model files directly. Verify the current API's data, lifecycle,
and availability guarantees before integrating it. Do not describe an API as
on-device solely because it is part of ML Kit; verify the specific feature and
runtime.

### TFLite / LiteRT

Support selected TFLite / LiteRT models through a dedicated adapter and the
runtime required by those models. Keep the catalog allow-listed; do not accept
arbitrary model URLs or files as executable model input.

For each candidate model, record:

| Field | Required detail |
| --- | --- |
| Identity | Name, version/revision, and task (STT, TTS, or LLM) |
| Provenance | Publisher and stable source |
| License | License and any redistribution/use restrictions |
| Runtime | TFLite / LiteRT version, operators, delegates, and ABI needs |
| Device fit | Supported Android versions, accelerator assumptions, tested device |
| Resources | Download size, peak memory, storage, and expected latency |
| Integrity | Expected checksum or other verifiable artifact identity |

Do not assume that every `.tflite` model is compatible with every TFLite /
LiteRT runtime or delegate. Validate tokenizer, tensor shapes, input/output
contracts, quantization, and model-specific preprocessing/postprocessing.

### ONNX Runtime for Smart Turn v3.2 and Kokoro TTS

ONNX Runtime is allow-listed only for the pinned Smart Turn v3.2 semantic
end-of-turn model and the pinned Kokoro `af_heart` TTS graph below. Smart Turn's
runtime path stays behind its replaceable turn-detector interface; this does not
add open-ended support for arbitrary ONNX models. The speech-android reference uses a pinned dynamic-int8
ONNX graph (`smart-turn-v3.2-int8.onnx`, 11,123,370 bytes) with ONNX Runtime on
CPU. Its model manager pins source revision
`b48fdbe20772bcec1fef02f4a1a355236ef6359e`; its README documents a BSD-2-Clause
license. Verify the exact artifact and license during implementation. Do not
assume a TFLite runtime can load the ONNX artifact directly.

For Smart Turn, keep the model optional, version-pinned, and replaceable. Verify the upstream
license, artifact integrity, input sample rate/window, preprocessing, and
probability contract, and account for storage and startup cost. Start with the
reference CPU path to avoid NNAPI partition/fallback variability; benchmark
other execution options on Pixel 10 before changing it. If the model is
disabled, unavailable, or fails to load, report that state and use the
configured VAD-only endpoint policy. See the reference
[model manifest](https://github.com/soniqo/speech-android/blob/main/sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt)
and [integration test](https://github.com/soniqo/speech-android/blob/main/sdk/src/androidTest/kotlin/audio/soniqo/speech/SmartTurnTest.kt)
for the pinned artifact and exercised endpoint behavior.

The only ONNX TTS graph is the distilled `af_heart` student from
[`MithrilMan/teacher-in-the-loop`](https://huggingface.co/MithrilMan/teacher-in-the-loop),
pinned by `KokoroArtifact`: revision `115a6c0469632a84fbcc6c8ed0ad7b1e06154b92`,
9,168,506 bytes, SHA-256
`f3daed94c8fe1f7a90f14cd7fc8ead55c99a8e57dd0aac669e376882483222c7`. Its graph
maps padded Kokoro phoneme ids and a speed scalar to 24 kHz mono float samples.
The upstream model card marks the weights Apache-2.0. The app downloads into
app-private storage, verifies the pinned identity before loading the native
session, and reports missing/corrupt/load-failed states instead of falling back
to platform TTS. Piece-wise synthesis and playback preserve first-audible timing
and delivered-text accounting; device latency and audible quality remain unmeasured
(R-0261).

### External LLM API

The remote provider is an API integration, not a local model runtime. Planned
providers are OpenAI, OpenRouter, OpenCode Go, OpenCode Zen, DeepSeek, and
Hermes Agent API Server. Keep each provider's HTTP client/base URL,
authentication, model catalog, request/response schema, streaming, timeouts,
and error mapping behind an adapter. Do not expose provider-specific payloads
to the rest of the app.

An OpenAI-compatible request format does not guarantee identical model names,
feature support, authentication, quotas, or streaming behavior. Verify these
details against each provider's current official documentation. See
[LLM providers and connections](./llm-providers.md).

## Selection and fallback

- Expose only providers and models that are compatible with the selected task
  and available on this device.
- Make the active provider/model visible and persist a selection only after it
  has been validated.
- Explain why an option is unavailable, such as unsupported API, model not
  provisioned, missing model files, or insufficient device resources.
- Require an explicit user choice or documented product rule before switching
  from a remote API to a local model, or vice versa; the data and quality
  characteristics differ.
- Report model download, validation, initialization, and inference failures.
  Never treat a missing/corrupt model as a successful initialization.

## Model lifecycle

Do not bundle large model files by default. For app-managed model assets, use
app-private storage, bounded downloads, integrity verification, atomic
installation, cleanup of incomplete files, and a documented update/removal
path. Do not request broad storage access just to manage models.
