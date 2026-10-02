# Voice Latency Optimization Plan

## Goal

Make dialog feel continuous: show revisable words while the user is speaking,
start the reply quickly after the thought is complete, and begin audible speech
while the language model is still generating. Optimize the complete experience,
not a single model's inference time.

All latency and quality claims must come from repeated runs on the Pixel 10 or
from clearly labeled host-only tests. Keep raw audio on the device. Keep routine
traces free of audio, transcripts, prompts, API keys, and provider response
bodies. The supplied `Walking*` files are the user's own recordings and may be
used for local evaluation; do not commit those recordings.

Measured latency and accuracy progress, including the tested commit for each
run, is recorded in [voice-latency-results.md](./voice-latency-results.md).

## Current findings

- The normal pipeline is microphone capture → VAD/endpointing → ML Kit GenAI STT
  → turn orchestration → selected streaming LLM → platform TTS.
- Correlated, content-free turn tracing, a bounded in-memory store, JSONL export,
  and a trace formatter already exist. The regular `AppContainer` voice factory
  initially omitted the diagnostics sink; this branch now wires an app-scoped
  bounded sink/store into normal voice sessions. The trace formatter is not
  surfaced in the app.
- Orchestration already records selected provider/model/reasoning, LLM deltas,
  first text, usage when returned, TTS first-audible, delivered text counts,
  cancellation, and errors. This branch moves the provider first-text timer to
  after local request construction. It measures adapter + network + server wait
  together; transport sub-boundaries still need implementation.
- M25 provides host-only quality scorers, percentile aggregation, and endpoint
  sweeps. It does not run recognizers or consume `test_data`. The ten WAV files
  are 16 kHz mono 16-bit PCM, about 79.7 seconds total. The two `Walking*.m4a`
  files have no reference transcripts or committed metadata. Accuracy scores
  must wait for trusted labels; model-generated pseudo-labels are not ground
  truth.
- The attached test device is a Pixel 10 (`frankel`) on Android 17 / API 37. A
  first combined JVM/device test attempt did not reach test execution because
  `AndroidTtsInstrumentedTest` has a non-exhaustive `when` for
  `SelectedVoiceUnavailable`; track that compiler blocker before interpreting
  any test result.
- Current candidate priority is Moonshine Voice streaming STT (Tiny then Small)
  because its Android Kotlin SDK accepts audio chunks and emits partials.
  Compare the installed ML Kit engine first. Whisper tiny/base and Parakeet 110M
  are offline-recognition comparisons; their lack of native streaming changes
  the latency shape. Defer Parakeet 0.6B unless smaller candidates leave a
  quality gap. For TTS, retain platform TTS as baseline, then test Moonshine's
  streaming Kokoro integration for time-to-first-audio. Check every model and
  voice asset license before adding it to the app.

## Candidate, runtime, and license metadata

These sources establish published model/runtime properties, not this app's
performance. **No STT or TTS candidate has a Pixel 10 latency or quality result
yet.** Vendor-reported speed and streaming behavior below are hypotheses to
check on the attached device, not measured project results.

| Candidate | Runtime and intended comparison | Model / asset license and source |
| --- | --- | --- |
| Moonshine Voice Tiny Streaming and Small Streaming STT | The Android Kotlin SDK is published as `ai.moonshine:moonshine-voice:0.1.5`; stream 16 kHz audio and compare partial cadence with ML Kit. This repository wires it with `debugImplementation` only. The adapter downloads model files on first use into app-private storage; do not bundle them in the APK. | Moonshine says English and all streaming STT models are MIT; verify the pinned asset manifest and third-party runtime notices when updating. See [Android dependency setup](https://moonshine-voice.readthedocs.io/en/latest/using/adding-the-library/) and [Moonshine license](https://github.com/moonshine-ai/moonshine/blob/main/LICENSE). |
| Whisper tiny.en and base.en | OpenAI's upstream implementation is Python and transcribes a file in sliding 30-second windows. For Android, the proposed route is the separate `whisper.cpp` Android sample/native runtime, so this is an offline phrase-level baseline unless a chunked partial path is separately demonstrated. | OpenAI lists tiny.en at 39M parameters and base.en at 74M and licenses code/weights MIT. Android integration and model conversion are separate runtime work. See [OpenAI Whisper](https://github.com/openai/whisper) and [`whisper.cpp` Android sample](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android). |
| Parakeet TDT-CTC 110M English | NVIDIA's NeMo checkpoint has a sherpa-onnx int8 ONNX conversion and an Android-capable runtime. The cited sherpa model is an offline CTC recognizer, so compare full-phrase final latency/quality, not live partial latency. Conversion, Kotlin binding, ABI/package size, and Pixel memory use remain to be checked. | The NVIDIA model card identifies CC-BY-4.0; the sherpa-onnx runtime is Apache-2.0. The sherpa model page reports an approximately 126 MB archive; verify the downloaded artifact and attribution obligations before use. See [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt_ctc-110m), [sherpa-onnx 110M model instructions](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/pretrained_models/offline-ctc/nemo/english.rst), and [sherpa-onnx Android guidance](https://github.com/k2-fsa/sherpa-onnx/blob/master/android/README.md). |
| Android platform TTS | Keep as the baseline using an installed Android TTS engine/voice. `UtteranceProgressListener` exposes synthesized audio chunks on API 24+; its generation callbacks and playback callbacks mark different events, so measure first generated audio and first audible output separately. | No speech model is bundled by this app for the platform engine. Voice availability, data downloads, and terms depend on the selected installed engine/vendor; record engine and voice IDs and verify their terms for the chosen device. See [Android TTS progress callbacks](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener). |
| Moonshine Voice streaming Kokoro TTS | Candidate Kotlin/Android integration via the same Moonshine Voice SDK. Its 0.1.5 notes describe chunked text input and streamed audio output, including sub-sentence chunking; treat this as a published capability, not a Pixel time-to-first-audio result. | Kokoro-82M weights are Apache-2.0, but Moonshine explicitly says TTS/G2P data follow their own upstream licenses. Before selecting a voice, audit that voice bundle, G2P/lexicon, ONNX conversion, and required notices individually. Model and voice assets are downloaded on demand, not packaged in the app. See [Moonshine TTS data provenance](https://github.com/moonshine-ai/moonshine/blob/main/core/moonshine-tts/data/README.md), [Kokoro source/license](https://github.com/hexgrad/kokoro), and [Moonshine 0.1.5 release notes](https://github.com/moonshine-ai/moonshine/blob/main/CHANGELOGS.md). |

For every downloaded candidate, record the pinned asset revision, checksum,
source, license identifier, required notices, runtime version, and app-private
install/delete behavior. The Moonshine dependency and adapter must remain
debug-only until repeated Pixel 10 comparisons establish latency, transcription
quality, memory/thermal behavior, cancellation, and offline behavior. Any
release candidate must also pass a per-asset license and attribution review;
the model's headline license alone does not clear bundled voice or G2P assets.

## Work plan

### 0. Establish a trustworthy baseline

1. Record the checked-out branch, repository changes already present, Pixel
   model/build/API, app build variant, audio route, battery/thermal state, and
   test-data inventory. Keep existing user changes untouched.
2. Fix only blockers that prevent the requested checks. Run JVM tests and
   instrumented tests independently and record the exact command and result.
3. Run current ML Kit Basic and Advanced paths against repeatable fixtures. Use
   warm and cold runs; do not combine model download/initialization with steady
   state inference. Record unavailable-model results honestly.
4. Capture one baseline per route that can be tested reliably: supplied WAV
   input, built-in microphone, and the user's outdoor `Walking*` recordings.
   Replay to real-time recognizers at real-time speed; never feed ML Kit from a
   file as fast as it can be read.

**Exit:** a recorded baseline with configuration metadata, per-case outcomes,
and no inferred accuracy for unlabeled recordings.

### 1. Make timing observable in the running app

1. Wire the app-scoped bounded diagnostics store into `AppContainer` voice
   sessions and own/cancel its collector with the app container.
2. Correlate each voice turn across capture, VAD/endpoint, STT, orchestration,
   provider request, TTS synthesis/playback, persistence, and barge-in. Use
   monotonic timestamps and stable trace/turn IDs.
3. Add explicit timestamps for speech onset, endpoint decision, STT start, first
   partial, final transcript, request-build completion, transport dispatch,
   response headers, first SSE frame, first text delta, provider completion,
   first TTS synthesis output, first audible sample, and delivery/stop. Keep
   audio playback latency distinct from audio generation latency.
4. Attribute external time by provider/model/reasoning and separate context
   building, request serialization, network/server wait, first-token latency,
   inter-delta gaps, stream completion, and cancellation. Where an adapter does
   not expose a boundary, mark it unavailable rather than estimating it.
5. Provide a debug-only local viewer/export for content-free trace events,
   including buffer overflow/eviction counts. Do not persist traces or upload
   them. Keep production logging disabled by default.
6. Build a report joining raw event timings into per-stage and end-to-end
   p50/p90/p95/max summaries. Preserve cold/warm and failure/cancel rows.

**Exit:** a normal device voice turn yields an inspectable, redacted trace with
all available boundaries, and tests prove the values use monotonic time and
exclude sensitive content.

### 2. Turn supplied speech data into a repeatable harness

1. Add a local corpus manifest containing file path, encoding, sample rate,
   channels, duration, condition, provenance/consent, reference transcript
   status, and intended benchmark mode. Do not include local `Walking*` audio in
   Git.
2. Decode M4A locally to normalized 16 kHz mono PCM for consistent model input;
   preserve the source file and record decoder/version and resampling settings.
3. Use existing host M25 replays for deterministic orchestration and endpoint
   regression. Add an Android paced feeder that supplies the same timestamped
   chunks to the real STT contract at real-time rate. Do not mistake fake
   recognizer replay for a real-model result.
4. Require trusted transcripts before WER/CER or semantic error scoring. Keep
   transcript labels local until the user confirms whether they may be committed.
   Until labels exist, report latency, recognizer output availability, and
   failure rate only.
5. Automate repeated cold/warm runs, persist redacted JSONL/CSV reports with
   model/runtime/device conditions, and calculate nearest-rank percentiles.
   Capture CPU, memory, thermal, and battery deltas where Android exposes them.

**Exit:** a single command can run the same labeled or latency-only corpus
through an engine and produce a reproducible report without a person speaking.

### 3. Compare STT engines on identical audio

1. Measure the current ML Kit GenAI baseline first, including partial cadence,
   endpoint-to-final delay, failure/unavailable behavior, transcription quality
   on labeled clips, load cost, memory, CPU, and thermal impact.
2. Add Moonshine Voice Tiny behind the existing replaceable STT boundary as the
   first live-stream candidate. Verify its Android SDK, model availability,
   license, partial/final contract, cancellation, and offline behavior at
   runtime; compare Tiny then Small with the identical paced recordings.
3. Compare whisper.cpp tiny/base only if needed for accuracy or as an offline
   phrase-level baseline. Document the native C/C++ interop boundary required by
   the existing Kotlin-only project constraints; do not add it before the
   streaming candidate has been measured.
4. Compare sherpa-onnx Parakeet 110M if its model license and artifact provenance
   are verified. Treat it as offline phrase recognition unless a tested Android
   streaming path demonstrates otherwise. Keep 0.6B out of the first pass due
   to its size and memory cost.
5. Select a default only after comparing latency, WER/CER and critical meaning
   errors (names, numbers, negation, corrections, short acknowledgements),
   cold/warm behavior, availability, battery/thermal cost, and license. If the
   current ML Kit mode remains better overall, keep it.

**Exit:** side-by-side Pixel 10 results under identical conditions and an
explicit model-selection decision with limits and rollback path.

### 4. Optimize TTS after choosing the STT path

1. Establish platform TTS baseline for selected installed voice and route. Feed
   identical short and long answers both as one completed string and as a fixed
   streamed token schedule.
2. Measure first synthesis output, first audible audio, inter-chunk gaps,
   playback underruns, full-response duration, cancellation/barge-in stop,
   CPU/memory/thermal cost, and voice availability. Keep first audio audible
   separate from `speak()` start or synthesis callback time.
3. Test Moonshine's streaming Kokoro TTS first if its Android/Kotlin API and
   assets meet license/runtime requirements. Compare direct Kokoro ONNX only if
   the wrapper adds measurable latency or limits needed playback control.
4. Test other candidates only when there is a concrete quality or latency gap.
   A listening comparison is required before claiming quality comparable to
   ChatGPT Live or Gemini Live; automated signal checks alone cannot establish
   perceived voice quality.
5. Preserve sentence-aware streaming, backpressure, cancellation, delivered
   text accounting, and fast barge-in across all engines.

**Exit:** a measured voice choice with long-answer TTFA and chunk-gap results,
quality review, license record, and successful interruption behavior.

### 5. Tune the full dialog loop

Use per-stage traces to tune VAD onset, endpoint silence/Smart Turn, STT partial
cadence, LLM context length/reasoning/provider choice, streamed TTS chunk size,
audio route, and cancellation policy. Change one major variable at a time and
rerun the corpus plus Pixel end-to-end checks. Include false barge-ins (echo,
wind, road noise, music, acknowledgements), real first-word interruptions, short
and long pauses, corrections, network loss, provider errors, and unavailable
models. Set product budgets only from repeatable Pixel results; report medians
and tail values rather than one best run.

**Exit:** the selected configuration improves time-to-first-audible-speech
without an unacceptable recognition, interruption, availability, privacy, or
voice-quality regression.

## Report format and decision rules

Every report records run date, source revision, device/build/API, app variant,
route, locale, fixture and label provenance, engine/model/runtime versions,
cold/warm state, provider/model/reasoning, network condition, sample count,
percentile method, and failures/cancellations. Include at least:

- speech onset → first STT partial;
- speech end → finalized transcript and endpoint decision;
- endpoint → provider dispatch, first response byte/frame/text, and completion;
- speech end → first audible assistant audio and full response completion;
- first LLM text → first audible audio;
- user onset during playback → playback stopped and next capture resumed;
- WER/CER and critical-meaning errors only where verified references exist.

Keep raw per-run events available locally for review; publish only aggregated,
content-free measurements. A candidate wins only when it improves the relevant
latency distribution and meets the quality, stability, resource, privacy, and
license gates. An unavailable or failed engine is a measured outcome, not a
zero-latency result.

## Current execution status

- Branch: `optimization/voice-latency`.
- Pixel 10 is online over ADB.
- App-created voice sessions now feed content-free events to an app-scoped,
  bounded in-memory trace store; it is available through `AppContainer` and
  remains inaccessible through app UI/export controls.
- `.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest` passed:
  917 JVM tests (8 skipped, 0 failures) and 24 Pixel 10 instrumented tests
  (0 skipped, 0 failures). The focused diagnostics wiring test also passed.
- The first combined attempt reached Android test compilation and found the
  `SelectedVoiceUnavailable` exhaustive-`when` error; after fixing that case,
  the full run above passed.
- The local corpus replay harness is in progress. No sample-file latency or
  recognition-quality baseline has been measured yet.
- No baseline latency or model comparison has been measured yet.
