# Parakeet-EOU STT: streaming decode + VAD / End-of-Turn integration

Branch: `feat/parakeet-eou-streaming` (worktree `I:\Android_Projects\VoiceChat-parakeet-eou`).
Reference: `soniqo/Parakeet-EOU-120M-ONNX-INT8`, `soniqo/speech-swift`
(`ParakeetStreamingASR`), `soniqo/speech-core` (`parakeet_stt.cpp`).

This document is the **frozen interface contract** for the four parallel work
streams. Do not change a signature below without updating every consumer; if a
stream needs a different shape it must raise it, not fork the seam.

## Model facts (verified)

| Property | Value |
| --- | --- |
| Artifact | `soniqo/Parakeet-EOU-120M-ONNX-INT8`, license `cc-by-4.0` |
| Base | `nvidia/parakeet_realtime_eou_120m-v1` (NVIDIA Open Model License) |
| Encoder | `parakeet-eou-encoder.onnx`, 131,741,896 B, INT8 |
| Decoder | `parakeet-eou-decoder.onnx`, 15,757,826 B, FP32 |
| Joint | `parakeet-eou-joint.onnx`, 5,589,132 B, FP32 |
| Config/Vocab | `config.json` 524 B, `vocab.json` 17,437 B |
| SHA-256 (encoder) | `fcd0452f27a49d266a7b13a048f603798c66afe453838cd1ac685b30c1bae55c` |
| SHA-256 (decoder) | `205cd7d44b5320f81d82dd6683e49e05ba4d72e0564151eb21a86e172f69f488` |
| SHA-256 (joint) | `6fb4b7674c2988ff8a4aa9c9181c6f06990d318a828e9696c4619505d2c08457` |
| SHA-256 (config) | `db0d95f8575e6610f4f524c87d647d5bcd5a868ed5102044e5ccdf4352d5da4b` |
| SHA-256 (vocab) | `83fd42ad33dae1bd3ceee6c0bb6c625f314cf0b2dc8430be441ac1e2643d5c36` |
| Sample rate | 16 kHz mono float32 |
| Mel | 128 bins, nFFT 512, hop 160, win 400, pre-emphasis 0.97, Hann `periodic=false`, **zero** center padding, ln(x + 2^-24), **no normalization** |
| Streaming | chunk 64 mel frames (10,240 samples); advance 32 mel frames (5,120 samples) per encoder call |
| Tokens | EOU 1024, EOB 1025, blank 1026; `▁` (U+2581) → space |
| Languages | 25 European; no punctuation/capitalization; English/base model card says English-only |

Encoder I/O: `audio_signal` f32 `[1,128,64]`, `audio_length` i64 `[1]`,
`pre_cache` f32 `[1,128,9]`, `cache_last_channel` f32 `[17,1,70,512]`,
`cache_last_time` f32 `[17,1,512,8]`, `cache_last_channel_len` i64 `[1]` →
`encoded_output` f32 `[1,T,512]`, `encoded_length` i32 `[1]`, `new_pre_cache`,
`new_cache_last_channel`, `new_cache_last_time`, `new_cache_last_channel_len` i32.

Decoder: `token` i64 `[1,1]`, `h`/`c` f32 `[1,1,640]` → `decoder_output`,
`h_out`, `c_out`. Prime with blank before the first joint call.

Joint: `encoder_output` f32 `[1,1,512]`, `decoder_output` f32 `[1,1,640]` →
`logits` f32 `[1,1,1027]`.

Decode loop per encoder frame: up to 10 non-blank symbols; blank breaks;
EOU sets `eouDetected` and breaks; EOB is not emitted as text; confidence is
`exp(mean(logSoftmax(logit)))` over emitted tokens.

## Two finalization signals, one decision

1. **EOU (model-internal)** — the joint emits `<EOU>`. speech-swift debounces it
   (default 1280 ms of sustained EOU with no new tokens) because the head fires
   mid-word on disfluencies.
2. **VAD / bounded silence cap (external)** — `BoundedTurnEndpointPolicy`
   force-finalizes. This is the guarantee a trailing turn terminates.

Host measurement on the 10 local pseudo-labeled clips: with the 1280 ms debounce
the EOU head committed only **1 of 10** clips, while streaming decode still beat
naive full-utterance decode (mean WER 0.331 vs 0.846; host RTF ~0.10-0.14). So
the VAD/silence path must be the primary commit and EOU a *semantic
corroborator*, never the sole endpoint.

## Frozen seams

### `ParakeetEouConfig` (stream 1)

```
data class ParakeetEouConfig(sampleRate, numMelBins, nFft, hopLength, winLength,
    preEmphasis, encoderHidden, encoderLayers, subsamplingFactor, attentionContext,
    convCacheSize, decoderHidden, decoderLayers, vocabSize, blankTokenId, eouTokenId,
    eobTokenId, chunkMelFrames, outputMelFrames, preCacheSize) {
    val shiftSamples = outputMelFrames * subsamplingFactor * hopLength
    val chunkSamples = chunkMelFrames * hopLength
    companion object { fun pinned(): ParakeetEouConfig; fun fromConfigJson(text: String): ParakeetEouConfig }
}
```

### `ParakeetStreamingDecoder` (stream 1) — pure/JVM-testable

```
class ParakeetStreamingDecoder(vocab: ParakeetVocabulary, config: ParakeetEouConfig) {
    fun reset()
    fun accept(chunkSamples: FloatArray): DecodeResult   // exactly chunkSamples
    fun finalize(): DecodeResult
    fun forceEndOfUtterance(): DecodeResult              // VAD-driven; keeps caches
}
data class DecodeResult(val text: String, val isFinal: Boolean, val eouDetected: Boolean,
    val confidence: Float, val segmentIndex: Int)
```

Pure Kotlin: it owns the mel front-end and the greedy loop and calls an injected
`ParakeetEouRuntime` (stream 2). No `android.*`, no ONNX type.

```
interface ParakeetEouRuntime : AutoCloseable {
    val hidden: Int            // 512
    val decoderHidden: Int     // 640
    val logits: Int            // 1027
    fun encode(mel: FloatArray, melFrames: Int, state: EncoderState): EncoderOutput
    fun decode(token: Int, h: FloatArray, c: FloatArray): DecoderOutput
    fun joint(encoderFrame: FloatArray, decoderOutput: FloatArray): FloatArray
}
```

### `ParakeetEouSpeechToText` (stream 3)

Implements `com.voicechat.agent.contracts.SpeechToText`. Also exposes the EOU
signal for the turn policy:

```
interface EndOfUtteranceSignal {
    /** Monotonic millis offset of the last confirmed EOU in the current capture, or null. */
    fun lastEouOffsetMillis(): Long?
    fun consumeEou(): Boolean
}
```

### `EouTurnCompletionDetector` (stream 3)

Implements `contracts.TurnCompletionDetector`. At a VAD candidate pause:
`COMPLETE` when a confirmed EOU was seen within `config.eouWindowMillis` of the
pause, else `INCOMPLETE` (bounded cap still ends the turn). Never per-frame.

### Selection (stream 4)

`SttMode` gains `PARAKEET_EOU`; `VoiceSettings`/`PreferencesSettingsStore` gain
`sttMode` handling for it (already enum-based) plus a new
`parakeetEouInstalled` capability; `VoiceRuntimeSelection` carries the resolved
engine; `VoiceSessionAssembly` builds `ParakeetEouSpeechToText` when selected and
`MlKitSpeechToText` otherwise. Model install mirrors `SmartTurnModelStore`
(app-private, size + SHA-256 verified, atomic move, removable) but installs five
files.

## Non-negotiables

- Never claim a build/test/device run passed unless it was actually run; report
  the exact command and real outcome.
- No secret, transcript, or raw audio in logs or diagnostics.
- Model files are never bundled in the APK; app-private storage only.
- New risks go in `docs/risks-and-decisions.md` starting at `R-0240`;
  `docs/vad-endpointing.md` and `docs/voice-latency-optimization.md` are updated.
- `spotlessCheck` must pass; run `spotlessApply` before reporting done.

## Verified host reference

`parakeet_eou_host.py` (in this repo's `scripts/`) reproduces mean WER 0.331 /
0.846 and is the cross-check oracle for stream 1's Kotlin port.

## Verified end-to-end on Pixel 10 (2026-10-03)

The full voice path was run on a physical Pixel 10 (`57220DLCR002R6`) with the
debug build, the pinned model installed in app-private storage, and the UI in the
foreground:

1. **Capture from a file, not the microphone.** `DebugFileAudioInput`
   (`app/src/debug/.../debug/DebugFileAudioInput.kt`) replays a staged 16 kHz mono
   PCM16 WAV as 20 ms frames at real time, then keeps emitting silence so capture
   never ends on its own. It is selected by
   `DebugVoiceChatApplication.voiceAudioInputFactory()` when
   `files/debug-audio/input.wav` exists; with nothing staged the real microphone
   is used. It is compiled into the debug source set only.
2. **VAD/onset.** `EnergyVoiceActivityDetector` +
   `BoundedTurnEndpointPolicy` (built through `PolicyVoiceTurnDetector`) reported
   `SPEECH_STARTED` (onset), held a mid-utterance `CANDIDATE_PAUSE` as
   `SEMANTIC_INCOMPLETE` (EOU corroborator), and committed the trailing turn with
   `SILENCE_CAP`.
3. **Recognition.** `ParakeetEouSpeechToText` over the real ONNX runtime finished
   with `outcome=COMPLETED frames=777 chunks=25 dropped=0 chars=193`.
4. **LLM.** The committed transcript was passed to the persisted provider
   (`opencode-go` / `longcat-2.5-preview-free`) over the shared remote transport
   (`orchestration: turn start source=VOICE`, `llm: request … streaming=true`) and
   streamed back.
5. **TTS.** The streamed reply was synthesized by the on-device platform TTS
   (`tts: synthesis start … voice=en-us-x-tpf-local`); the dialog showed the user
   bubble, the streaming assistant bubble, and the `Speaking…` state.

The decoder-only replay (`scripts/run-parakeet-eou-replay.ps1`) is unchanged and
remains the content-free timing/accuracy harness. The end-to-end UI run is driven
by `scripts/run-parakeet-eou-voice-loop.ps1`, which installs the debug APK, stages
the WAV (and an optional developer credential), taps the talk control, and writes
a logcat capture plus periodic screenshots under `build/screens/`.

```powershell
.\scripts\run-parakeet-eou-voice-loop.ps1 `
  -InputPath "test_data/eng-000058-000ee09a.wav" -ListenSeconds 45
```

### Remaining open items (not blockers for the path)

- **Real-audio accuracy is not gated.** The end-to-end run proves the pipeline
  produces a transcript, not that the transcript is accurate; the only accuracy
  numbers are the 10 local pseudo-labeled clips (R-0240/R-0241).
- **EOU still commits rarely.** In the device runs the EOU corroborator reported
  `SEMANTIC_INCOMPLETE` and the silence cap ended the turn, consistent with the
  host measurements; the EOU window/capacity remain provisional (R-0240).
- **Long replies exceed the TTS delivery wait.** A ~2 000-character reply hit the
  60 s `TurnOrchestrator` delivery timeout before playback finished; the turn
  still settled truthfully. This is a TTS/delivery bound, not a Parakeet issue.

