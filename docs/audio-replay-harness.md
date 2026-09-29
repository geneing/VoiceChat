# Deterministic Audio Replay Harness

This document describes the M03 replay and speech-test foundation: a pure-JVM
way to drive the capture, STT, VAD, and turn-completion boundaries from frozen
PCM fixtures instead of a live microphone. It complements the strategy in
[validation.md](./validation.md) and
[voice-quality-and-latency.md](./voice-quality-and-latency.md).

> **Important — replay cannot feed the real ML Kit STT engine.** ML Kit GenAI
> Speech Recognition (M08) requires audio delivered to its file descriptor at a
> real-time rate (about 32 KB per second) and does not support file-backed
> descriptors that read at full speed. This harness replays fixtures as fast as
> possible, so it can drive the `SpeechToText` **contract** and fakes
> deterministically but must not be wired to the real engine. On-device STT
> checks use live capture or an explicitly real-time-paced feeder. Source:
> <https://developers.google.com/ml-kit/genai/speech-recognition/android>.

Implementation lives in `app/src/main/kotlin/com/voicechat/agent/replay/`. The
package is pure Kotlin: it contains no `android.*` / `androidx.*` imports, no
network types, and no microphone code, so it runs in ordinary JVM unit tests.
`ReplayIsolationTest` enforces that at the source level.

## What the harness provides

| Type | Role |
| --- | --- |
| `ReplayAudioInput` | `AudioInput` that emits a fixture's frames; the same contract live capture implements. |
| `ReplaySpeechToText` | `SpeechToText` that consumes replayed audio and emits the fixture's labeled partial/final hypotheses. |
| `ReplayVoiceActivityDetector` | `VoiceActivityDetector` that emits the fixture's labeled onset, pause, and resume events. |
| `ReplayTurnCompletionDetector` | `TurnCompletionDetector` that replays labeled semantic decisions (and reports `UNAVAILABLE` when exhausted). |
| `PcmFixture` | Manifest plus raw 16-bit PCM samples. |
| `FixtureManifest` / `FixtureManifestCodec` | Typed manifest and its deterministic text codec. |
| `DeterministicSpeechGenerator` | Seedable in-repo stand-in for harness TTS. |
| `AudioTransformation` (sealed) | Seedable degradations: gain, clipping, noise, competing speech, echo, reverberation, compression, codec artifacts. |
| `ReplayFixtures` / `FixtureVariants` | Canonical base fixture and the repeatable condition variants. |

The adapters implement the **M02 contracts unchanged**; replay does not fork a
parallel pipeline. Later milestones (M08 STT, M09 VAD, M10 Smart Turn) can reuse
the same fixtures against their real engines.

## Determinism

Replaying a fixture must yield identical frames, labels, and transformation
metadata every time:

- The generator and every transformation use an in-repo SplitMix64 generator
  (`SplitMix64.kt`) seeded from the manifest, not `kotlin.random.Random`.
- All transcendental math goes through `java.lang.StrictMath` (fdlibm) so output
  is bit-identical across JVMs and platforms.
- Replayed `AudioFrame.capturedAtNanos` is `0`; the frame sequence depends only
  on the fixture's samples and frame size.
- The manifest records a SHA-256 of the exact PCM bytes; `PcmFixture` refuses to
  construct if the samples and hash disagree.
- `FixtureManifestCodec` writes labels and transformations in a fixed order, so
  encoding the same manifest twice is byte-identical.

`ReplayDeterminismTest`, `FrozenFixtureReplayTest`, and
`FixtureVariantCoverageTest` assert these properties.

## Fixture manifest

The manifest is a line-oriented `key=value` document (the value is everything
after the first `=`, so transcripts may contain `=`). Comments start with `#`.
The committed example is
`app/src/test/resources/replay/frozen/synthetic-pause-resume.manifest`. Fields:

- `fixture.id`, `source.*` — source identity, including `origin`
  (`SYNTHETIC` / `HUMAN`), a human-readable name and description, the license,
  and provenance (generator/seed or the human intake reference).
- `format.sampleRateHz`, `format.channelCount` — sample rate/format. The app's
  capture path is 16 kHz mono (`AudioFormat.MONO_16_KHZ`).
- `engine.*` — the engine and model the labels were produced against. For
  synthetic fixtures this names the harness generator; for human fixtures it
  must name the real STT engine/model used to label the clip.
- `frame.sizeSamples` — replay frame size (320 samples = 20 ms at 16 kHz).
- `pcm.sampleCount`, `pcm.sha256` — frozen content identity.
- `seed` — the generation seed.
- `label.finalTranscript` — the expected final transcript.
- `labels.transcript.*` — expected hypotheses at frame offsets (`text`,
  `isFinal`), which exercise revised partials before finalization.
- `labels.vad.*` — expected `SPEECH_STARTED` / `CANDIDATE_PAUSE` /
  `SPEECH_RESUMED` events at frame offsets, which exercise pause/resume input.
- `labels.turnCompletion.*` — expected semantic decisions at candidate pauses.
- `transformations.*` — the applied chain, in order, each with its `kind`,
  `seed`, and typed parameters.

Raw PCM is stored as headerless 16-bit signed little-endian mono — exactly the
format the STT custom-audio contract expects (`docs/decisions.md` §2.1) — so a
fixture needs no container or codec to replay.

## Synthetic fixtures and variants

Synthetic fixtures use a seedable in-repo generator instead of a cloud TTS
service, so replay never needs a network, credentials, or a live engine. The
generator renders a phrase as formant-like voiced syllables with configurable
gaps and pauses (`|` marks a pause). It is deliberately **not intelligible
speech**: it exists to drive the pipeline, endpointing, and degradation
transforms deterministically. Accuracy claims must come from the permissioned
human corpus.

`FixtureVariants.standardVariants()` derives one repeatable variant per
required condition — street noise, car noise, competing speech, echo,
reverberation, gain, clipping, compression, and codec artifacts — each from the
base fixture with a fixed seed and one transformation. Variants are generated at
test time, not committed, and keep the base labels because synthetic labels
describe the source phrase rather than a recognition result.

### Regenerating the frozen fixture

One small synthetic fixture is committed
(`app/src/test/resources/replay/frozen/synthetic-pause-resume.pcm`, ~49 KB) so
replay is demonstrably byte-stable. To refresh it after an intentional change to
the generator or manifest format:

```powershell
$env:REGENERATE_REPLAY_FIXTURES = "1"
.\gradlew.bat :app:testDebugUnitTest --tests "com.voicechat.agent.replay.FrozenFixtureRegenerationTest"
```

`FrozenFixtureReplayTest` then proves the committed bytes and manifest still
match the generator output exactly. Routine CI never writes to the source tree.

## Human-speech fixture intake

Synthetic fixtures are broad but not representative of real accents,
disfluencies, and microphone behavior. Human recordings are added only through
this intake process — an unlicensed or unconsented recording must never be
committed.

1. **Consent.** Obtain explicit, documented consent from every speaker for the
   recording to be used for speech-recognition testing and stored in this
   repository. Consent is not implied by a recording being "public" or by it
   already existing on a device.
2. **License.** Record a license that permits redistribution and test use (for
   example a written consent statement or a permissive license). A file with no
   verifiable origin and license is rejected, not "temporary".
3. **Anonymity.** Do not include a speaker's name, contact details, or other
   personal data in the fixture, manifest, or commit message unless the speaker
   consented in writing. Prefer a pseudonymous speaker label.
4. **Format.** Convert to raw 16-bit signed little-endian mono PCM at the
   fixture sample rate (16 kHz unless a fixture documents otherwise), trim to the
   region of interest, and keep the clip small.
5. **Labels.** Produce expected transcripts with the real on-device STT
   engine/model under test and record that engine/model in `engine.*`. Do not
   infer labels from a different engine.
6. **Manifest.** Set `source.origin=HUMAN`, name the intake reference in
   `source.provenance`, and state the consent/license in `source.license`.
7. **Placement.** Commit the PCM and manifest under
   `app/src/test/resources/replay/human/` only after 1–6 are satisfied. Keep
   these test resources out of the APK; they are not app assets.

`test_data/` may hold local `.m4a` scratch recordings (for example the untracked
`Walking *.m4a` files in a developer checkout). They are **not** part of the
corpus, their licensing/consent is unverified, and they must not be copied into
the repository or committed until they pass this intake process.

## Synthetic and human results stay separate

`FixtureOrigin` distinguishes the two corpora, and every result must carry the
fixture's origin, engine/model, and transformation metadata. Synthetic results
measure pipeline determinism and robustness to degradations; human results
measure actual recognition quality. Never merge them into one accuracy number
(see [voice-quality-and-latency.md](./voice-quality-and-latency.md)).

## Guarantees and out of scope

- Replay needs no credentials, network, downloaded model, or microphone.
- The harness does not implement any real STT, VAD, or semantic endpoint model
  and makes no accuracy claims; those arrive in M08–M10.
- It does not add runtime dependencies and does not touch the M02 `Diagnostics`
  contract. Turn tracing/timing is M04.
