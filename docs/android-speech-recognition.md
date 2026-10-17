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
speaker. **Limitation:** a recognizer that ignores `EXTRA_AUDIO_SOURCE` (possible
per the platform docs, and on API 31-32 where the extra does not exist) opens the
microphone instead; that is recorded as R-0243, not hidden.

### End of speech

The recognizer's `onEndOfSpeech` is surfaced as `SttEvent.EndOfSpeech` and
recorded, but it is **not** the authority for ending a turn. The bounded M09
VAD/Smart Turn endpoint closes the turn and its input; the adapter then closes
the pipe, calls `stopListening()`, and waits, bounded (3 s), for the final
`onResults`. This is deliberate: a request-time engine signal must not truncate a
still-speaking user, and a silence cap must still commit a finished turn. In
short, VAD/turn detection make the engine's end-of-speech robust rather than
being replaced by it.

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
content-free. No Pixel 10 run has been recorded yet, so no latency claim is made
(R-0244).
