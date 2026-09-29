# Android Device Notes

The observations below come from the neighboring
[GVP project README](https://github.com/m15-ai/droidkaigi2026-app-gvp/blob/main/README.md).
They are useful hypotheses and test cases for Pixel 10, not universal Android
facts or settings to copy unchanged. This app has a different STT/LLM/TTS
pipeline and must measure its own behavior.

## Pixel 10 capture level and audio path

GVP reports that its `VOICE_COMMUNICATION` capture path with platform AEC/NS/AGC
was unusually quiet on Pixel 10: loud speech measured about `0.0126 RMS` against
a `0.027` voiced-energy threshold before an 8x software-gain adjustment. That
starved its VAD and recognizer. This warns against assuming a nominal Android
audio source produces usable PCM levels.

For this app, expose diagnostic capture levels and validate the complete path
with repeatable PCM tests. Calibrate gain/noise floor by route and device;
prevent clipping, preserve recognizer-expected input format, and measure STT
accuracy with processing both enabled and disabled. Do not hard-code GVP's 8x
gain or RMS threshold.

## Speakerphone echo and barge-in

GVP reports good hardware echo cancellation on its Pixel 10 setup, with
residual echo around `0.002 RMS` versus user speech around `0.04-0.08 RMS`.
That made a `0.025` in-TTS energy threshold useful in that app. It also uses
sustained onset, gap bridging, debounce, and a TTS-onset grace period to manage
double-talk chopping and echo. Such values are specific to its microphone,
speaker, playback volume, AEC path, and policy.

Our acceptance tests must separately cover the built-in speaker and headset
paths. Test first-word interruption latency, false interrupts caused by the
assistant's own audio, short acknowledgements, music/noise, and AEC behavior
during double-talk. In particular, do not copy a long TTS-onset grace interval
without proving it does not make real user barge-in feel unresponsive. Consider
reference-based acoustic echo cancellation only if measurements show the
platform path is insufficient.

## On-device inference contention

GVP reports that its ML Kit GenAI Advanced ASR and Gemini Nano LLM contended
when both used AICore: LLM responses that were around 2 seconds in its setup
slowed beyond 6 seconds. Its Basic/SODA recognition path did not show the same
contention. This is an implementation/device observation, not a guarantee for
this app or future runtime versions.

The normal LLM path here is external, but optional AICore / ML Kit and
TFLite/LiteRT paths still need concurrent load tests. Measure end-to-end latency,
thermal behavior, and failures with STT, LLM, and TTS active together; surface
which engine/model is active and avoid silently changing it.

## Repeated-turn context cost

GVP documents repeated-turn latency increasing from about `0.8 s` to `2.0 s`
as it rebuilt a longer LLM prompt in its on-device implementation. For this
app, measure provider/model latency as conversation context grows. Keep
persisted chat history distinct from bounded inference context; use only
provider-supported caching or runtime-specific persistent sessions, and make
sure interrupted turns and history edits remain correct.

## Smart Turn v3.2 in speech-android

The speech-android README describes Pipecat Smart Turn v3.2 as an optional
audio-native classifier that evaluates the last eight seconds of the current
turn. Its pipeline first uses VAD to identify a confirmed pause, then asks the
classifier whether that pause completes the thought. This preserves the useful
separation between cheap, continuous speech-activity detection and less
frequent semantic endpoint inference.

The reference configuration uses a `0.5` completion-probability threshold and
a `2.0 s` maximum-silence cap. When Smart Turn vetoes a pause, resumed speech
stays in the same turn; the silence cap still guarantees an endpoint for a
trailing speaker. In the reference SDK, Smart Turn is opt-in, adds a pinned
dynamic-int8 ONNX graph (`11,123,370` bytes; source revision
`b48fdbe20772bcec1fef02f4a1a355236ef6359e`) and runs once per VAD-confirmed
pause rather than on every frame. Its README documents the model as BSD-2-Clause;
verify the exact artifact's license before use. The Android integration uses
CPU to avoid NNAPI partition/fallback variability. These are useful design
examples, not production-tuned values for this app. The
[decision record](./decisions.md#33-smart-turn-v32-artifact-verified-separately)
pins the exact re-export revision, size, and checksum to use, and marks the
upstream Pipecat artifact separately.

The reference also has both configuration tests (probability and silence
limits) and an Android integration test that loads the optional model, feeds
TTS-generated speech as fixed-size 16 kHz PCM frames, confirms an early pause
is held, and verifies the maximum-silence cap eventually ends the turn. Adopt
this layered test pattern and add real/recorded human speech to evaluate
prosodic generalization.

The model is ONNX, in addition to this project's AICore / ML Kit and TFLite /
LiteRT options. Plan ONNX Runtime support specifically for this artifact behind
a replaceable turn-detector interface; do not imply general ONNX model support
or assume LiteRT loads it. Pin the artifact revision, verify license and input
contract, and compare CPU/accelerator latency, footprint, false commits, and
false holds before enabling it by default. This separation also makes it
possible to replace Smart Turn later without changing the voice-session
orchestration contract.

## Architecture patterns to consider

GVP's documented architecture is useful prior art: Kotlin with Compose and a
state holder, coroutine Flow for streamed state/events, explicit audio/STT/LLM/
TTS interfaces, a router for selectable engines, a conversation repository,
and a foreground service for active microphone sessions. Adopt the separation
of concerns where it fits, but do not copy its energy thresholds, fixed gain,
specific models, or device assumptions. See
[architecture](./architecture.md) and
[voice quality and latency](./voice-quality-and-latency.md).

For implementation specifics, see
[speech-android's end-of-turn guide](https://github.com/soniqo/speech-android/blob/main/README.md#end-of-turn-detection),
[SpeechConfig](https://github.com/soniqo/speech-android/blob/main/sdk/src/main/kotlin/audio/soniqo/speech/SpeechConfig.kt),
[ModelManager](https://github.com/soniqo/speech-android/blob/main/sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt),
[SmartTurnTest](https://github.com/soniqo/speech-android/blob/main/sdk/src/androidTest/kotlin/audio/soniqo/speech/SmartTurnTest.kt),
and [configuration tests](https://github.com/soniqo/speech-android/blob/main/sdk/src/test/kotlin/audio/soniqo/speech/SpeechConfigTest.kt).

speech-android itself keeps the Android-facing Kotlin SDK relatively thin,
with a JNI bridge into a shared speech-core pipeline and model implementations
behind explicit VAD/STT/TTS contracts. For this Kotlin-first app, the
transferable lesson is a narrow session/pipeline API, replaceable engine
interfaces, and explicit model lifecycle—not a requirement to add C++, JNI, or
that repository's module layout. Keep such complexity out unless a measured
need justifies it.
