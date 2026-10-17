# Android platform speech recognition (M27)

This milestone replaces the app-managed **Parakeet-EOU ONNX** STT engine with the
standard Android speech-recognition API, `android.speech.SpeechRecognizer`
(`docs/decisions.md` §2.1). ML Kit GenAI Speech Recognition stays available as an
alternative engine on the same app boundary; the platform recognizer is the
default.

## Why change

- The platform API ships the on-device recognizer with the device; the app no
  longer downloads, verifies, or stores a 150 MB model set.
- It provides standard `onPartialResults` / `onResults` / `onEndOfSpeech`
  callbacks instead of a hand-written streaming decoder.
- Recognition can still be driven from the PCM the app already captures, so the
  same audio path serves the live microphone and a staged test file.

## Engine and availability

- Family `SttEngineFamily.ANDROID_SPEECH`, single mode `SttMode.ANDROID_SPEECH`,
  engine id `android-speech-recognizer`, model id `android-speech-on-device`.
- `AndroidSpeechSttStatus.check` reports the engine **ready only when the device
  exposes an on-device recognizer** (`SpeechRecognizer.isOnDeviceRecognitionAvailable`).
  A device with only a network recognizer is reported unavailable with a typed
  reason — the app never routes speech to a server on the user's behalf. This is
  the same "never claim ready, never silently fall back" rule the rest of the STT
  layer follows.
- In the catalog (`SttEngines.catalog`) the platform engine is listed first, so
  `SttEngines.preferred` picks it whenever it is ready; an explicit persisted ML
  Kit choice is still honoured (R-0181).

## Adapter: `AndroidSpeechRecognizerSpeechToText`

One `SpeechRecognizer` is created per recognition turn and released when the turn
ends. Platform calls run on the main thread, as the API requires.

### Audio path (`EXTRA_AUDIO_SOURCE`)

The recognizer's custom-audio source (`RecognizerIntent.EXTRA_AUDIO_SOURCE`,
`EXTRA_AUDIO_SOURCE_{CHANNEL_COUNT,ENCODING,SAMPLING_RATE}`, API 33+) is fed the
same 16 kHz mono 16-bit PCM frames the VAD already sees:

1. the M02 `AudioFrame` flow is drained into a small channel;
2. a writer coroutine encodes each frame (`PcmFraming.toLittleEndianPcm`) into the
   pipe the recognizer reads;
3. decoupling the drain from the pipe write keeps "input ended" observable even
   if the recognizer never reads the pipe (see R-0243).

This gives one microphone owner and one code path for live capture and replay.
It is also why the app can replay a recorded file with no host conversion and no
speaker. **Confirmed on Pixel 10 (M27):** the on-device recognizer reads the piped
PCM and returns partials and a final transcript for a staged clip. **Limitation:**
`EXTRA_AUDIO_SOURCE` is API 33+, and per the platform docs a recognizer may ignore
it ("no effect") and open the microphone instead; on API 31-32 the extra does not
exist. In that case live recognition still works from the microphone, but the
file-replay path cannot drive it (R-0243).

### End of speech

The recognizer's `onEndOfSpeech` is surfaced as `SttEvent.EndOfSpeech` and timed
(`END_OF_SPEECH_MILLIS`), but it is **not** a turn boundary. Measured on Pixel 10
(M27), the on-device recognizer emits `onEndOfSpeech` well before the utterance
ends (14.3 s before the final on one 22.7 s clip) and keeps streaming partials;
`onResults` arrives only when the custom audio closes. So the bounded M09
VAD/Smart Turn endpoint closes the turn and its input, the adapter closes the
pipe, and `onResults` follows (measured 150-350 ms later). The adapter never
calls `stopListening()` while the recognizer reads the pipe and never stops from
inside the `onEndOfSpeech` callback — both produced `ERROR_CLIENT` on the device.
This is the "use the API's end-of-speech but let VAD/turn detection make it
robust" behavior: VAD decides the turn, and the engine signal is corroborating
metadata.

Partial hypotheses become interim `SttEvent.Result`s through `SttResultAssembler`
(monotonic revisions, stale interims dropped by the coordinator); the final
`onResults` becomes the single final transcript, and `ERROR_NO_MATCH` /
`ERROR_SPEECH_TIMEOUT` become a clean empty result (a "no speech" turn) rather
than a failure.

### Logging

Every callback is logged: `onReadyForSpeech`, `onBeginningOfSpeech`,
`onRmsChanged` (verbose), `onBufferReceived` (verbose), `onPartialResults`,
`onEndOfSpeech`, `onResults`, `onError`, `onEvent`. Partial text is logged at
**verbose** level only (`AppLog.v`, off in release); the structured logs and the
`DiagnosticsSink` carry only counts, stable codes, and timings
(`docs/logging.md`, `docs/privacy-and-security.md`).

## Streaming a sound file (development and testing)

Two paths drive the same pipeline from a file:

- **Debug UI path.** With `input.wav` (16 kHz mono PCM16) or `input.m4a` staged in
  app-private `files/debug-audio/`, `DebugFileAudioInput` replays it in real time
  as 20 ms frames forever (trailing silence), so the mounted voice session runs
  exactly as with a microphone. M4A is decoded with `DebugM4aDecoder`
  (MediaExtractor/MediaCodec → 16 kHz mono). Both are debug-only.
- **Instrumented harness path.** `SttPipelineHarnessInstrumentedTest` stages one
  or more WAV/M4A clips and measures per-stage latency (below).

`scripts/run-stt-pipeline-harness.ps1` defaults to
`test_data/Walking .m4a` and `test_data/eng-000001-000081dd.wav`, and stages any
list of clips:

```powershell
.\scripts\run-stt-pipeline-harness.ps1
.\scripts\run-stt-pipeline-harness.ps1 -InputPath test_data\Walking .m4a, test_data\eng-000001-000081dd.wav -SmartTurn
```

## Latency measurement

The harness (`-e sttHarnessRunId <id>`, opt-in) replays each clip through
capture → VAD/onset → platform STT → bounded endpointing and writes a
content-free `summary.csv` / `stages.csv` beside the clip, plus a
`STT_HARNESS_RESULT` log line. It reports:

| Column | Meaning |
| --- | --- |
| `capture_start_to_first_partial_ms` | time to first partial recognition |
| `onset_to_first_partial_ms` | VAD speech onset to first partial |
| `vad_endpoint_to_final_ms` | VAD end-of-speech to the delivered final text |
| `end_of_speech_to_final_ms` | recognizer `onEndOfSpeech` to final |
| `input_end_to_final_ms` | last real-audio frame to final |
| `partial_count` | number of partial revisions |

With `-e sttHarnessLogTranscripts true` each partial and the final text are also
logged to logcat (`SttHarness`, `STT_HARNESS_TRANSCRIPT`); the CSV stays
content-free.

### Pixel 10 device run (2026-10-16, M27)

`Pixel 10 (57220DLCR002R6)`, Android 17 / API 37, on-device recognizer, VAD-only
(no Smart Turn). Two staged clips, one run each:

| clip | audio | first partial (capture start) | onset to first partial | VAD endpoint to final | input end to final | partials | final |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `Walking .m4a` | 22.7 s | 8077 ms | 1131 ms | 343 ms | 167 ms | 43 | "Latest news please" |
| `eng-000001-000081dd.wav` | 13.0 s | 1608 ms | 585 ms | 269 ms | 1542 ms | 81 | "What is the complete government process for applying for a passport for a minor under the age of 16 including all necessary documentation parental consent forms and Associated official fees" |

Both finished `FINAL`. The engine's `onEndOfSpeech` fired 14273 ms before the
final on the long clip, confirming it is not a turn boundary (VAD end-of-speech
is). This resolves the file-replay and end-of-speech questions (R-0243, R-0244);
the numbers come from one recording each and are not accuracy claims.
