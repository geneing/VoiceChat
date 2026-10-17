package com.voicechat.agent.tts.kokoro

import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.tts.TtsEngineAvailability
import com.voicechat.agent.tts.TtsEngineEvent
import com.voicechat.agent.tts.TtsEngineFailureKind
import com.voicechat.agent.tts.TtsOutputRoute
import com.voicechat.agent.tts.TtsVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class KokoroTtsEngineTest {
    @Test
    fun `Started follows first audible callback and is not repeated for later pieces`() =
        runBlocking {
            val audibleStarted = CompletableDeferred<Unit>()
            val returnedFromFirstPlay = CompletableDeferred<Unit>()
            val synthesizer = FakeSynthesizer()
            val sink =
                FakeSink { samples, onAudible ->
                    if (samples[0] == 1f) {
                        assertEquals(1, synthesizer.synthesisCount)
                        onAudible()
                        audibleStarted.await()
                        returnedFromFirstPlay.complete(Unit)
                        true
                    } else {
                        // This piece fails before it can report any audible output.
                        throw IllegalStateException("playback test failure")
                    }
                }
            val engine = engine(sink = sink, synthesizer = synthesizer)
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)
            assertEquals(listOf(KokoroArtifact.DEFAULT.id), engine.installedVoices().map { it.id })
            assertTrue(engine.installedEngines().isEmpty())

            val events = mutableListOf<TtsEngineEvent>()
            val collection =
                launch {
                    engine.speak("two pieces", UtteranceId("first-audible")).collect { event ->
                        events += event
                        if (event is TtsEngineEvent.Started) audibleStarted.complete(Unit)
                    }
                }
            withTimeout(2_000) { returnedFromFirstPlay.await() }
            collection.join()

            assertEquals(1, events.count { it is TtsEngineEvent.Started })
            assertEquals(TtsOutputRoute.BUILTIN_SPEAKER, (events.first() as TtsEngineEvent.Started).route)
            assertEquals(TtsEngineFailureKind.PLAYBACK_FAILED, (events.last() as TtsEngineEvent.Failed).kind)
            engine.close()
        }

    @Test
    fun `piece that never becomes audible does not emit Started`() =
        runBlocking {
            val engine =
                engine(
                    sink = FakeSink { _, _ -> false },
                    chunker = { text -> listOf(KokoroPhonemeChunk(text, text)) },
                )
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val events = engine.speak("not audible", UtteranceId("silent")).toList()

            assertEquals(0, events.count { it is TtsEngineEvent.Started })
            assertEquals(TtsEngineEvent.Interrupted(UtteranceId("silent"), ""), events.last())
            engine.close()
        }

    @Test
    fun `multi-piece utterance plays in order`() =
        runBlocking {
            val played = mutableListOf<Float>()
            val sink =
                FakeSink { samples, onAudible ->
                    played += samples.first()
                    onAudible()
                    true
                }
            val engine = engine(sink = sink)
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val events = engine.speak("two pieces", UtteranceId("ordered")).toList()

            assertEquals(listOf(1f, 2f), played)
            assertEquals(TtsEngineEvent.Completed(UtteranceId("ordered")), events.last())
            engine.close()
        }

    @Test
    fun `interruption reports only complete pieces`() =
        runBlocking {
            val secondPieceStarted = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var playCount = 0
            val sink =
                FakeSink { _, onAudible ->
                    playCount += 1
                    if (playCount == 1) {
                        onAudible()
                        true
                    } else {
                        secondPieceStarted.complete(Unit)
                        stopped.await()
                        false
                    }
                }.also { it.onStop = { stopped.complete(Unit) } }
            val engine = engine(sink = sink)
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val result = async { engine.speak("two pieces", UtteranceId("interrupt")).toList() }
            withTimeout(2_000) { secondPieceStarted.await() }
            engine.stop()

            val terminal = result.await().last()
            assertEquals(TtsEngineEvent.Interrupted(UtteranceId("interrupt"), "first"), terminal)
            engine.close()
        }

    @Test
    fun `stop interrupts queued utterance before it starts`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val sink =
                FakeSink { _, onAudible ->
                    onAudible()
                    firstStarted.complete(Unit)
                    stopped.await()
                    false
                }.also { it.onStop = { stopped.complete(Unit) } }
            val engine = engine(sink = sink)
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val first = async { engine.speak("first", UtteranceId("playing")).toList() }
            withTimeout(2_000) { firstStarted.await() }
            val second = async { engine.speak("second", UtteranceId("queued")).toList() }
            // Let the cold flow enqueue before stop interrupts the active and queued requests.
            kotlinx.coroutines.delay(30)
            engine.stop()

            assertEquals(TtsEngineEvent.Interrupted(UtteranceId("playing"), ""), first.await().last())
            assertEquals(TtsEngineEvent.Interrupted(UtteranceId("queued"), ""), second.await().last())
            engine.close()
        }

    @Test
    fun `synthesis exception maps to synthesis failure`() =
        runBlocking {
            val engine = engine(synthesizer = FakeSynthesizer(fail = true))
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val event = engine.speak("synth fail", UtteranceId("synth-fail")).toList().last()

            assertEquals(TtsEngineEvent.Failed(UtteranceId("synth-fail"), TtsEngineFailureKind.SYNTHESIS_FAILED), event)
            engine.close()
        }

    @Test
    fun `playback exception maps to playback failure`() =
        runBlocking {
            val engine = engine(sink = FakeSink { _, _ -> throw IllegalStateException("test playback error") })
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val event = engine.speak("play fail", UtteranceId("play-fail")).toList().last()

            assertEquals(TtsEngineEvent.Failed(UtteranceId("play-fail"), TtsEngineFailureKind.PLAYBACK_FAILED), event)
            engine.close()
        }

    @Test
    fun `missing persisted voice and corrupt graph are not reported ready`() =
        runBlocking {
            val missing = engine(voiceId = KokoroArtifact.DEFAULT.id, modelState = KokoroModelState.Missing)
            assertEquals(
                TtsEngineAvailability.SelectedVoiceUnavailable(KokoroArtifact.DEFAULT.id, Locale.US),
                missing.initialize(),
            )
            missing.close()

            val corrupt = engine(modelState = corruptState())
            assertTrue(corrupt.initialize() is TtsEngineAvailability.Unavailable)
            assertTrue(corrupt.installedVoices().isEmpty())
            corrupt.close()
        }

    @Test
    fun `unsupported locale reports no voice`() =
        runBlocking {
            val engine = engine(locale = Locale.forLanguageTag("es-ES"))

            assertEquals(TtsEngineAvailability.NoOnDeviceVoice(Locale.forLanguageTag("es-ES")), engine.initialize())
            engine.close()
        }

    @Test
    fun `punctuation-only text completes without Started`() =
        runBlocking {
            val engine = engine(chunker = { emptyList() })
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            val events = engine.speak("...", UtteranceId("punctuation")).toList()

            assertEquals(listOf(TtsEngineEvent.Completed(UtteranceId("punctuation"))), events)
            assertFalse(events.any { it is TtsEngineEvent.Started })
            engine.close()
        }

    @Test
    fun `close is idempotent and closed engine refuses new speech`() =
        runBlocking {
            val synthesizer = FakeSynthesizer()
            val sink = FakeSink { _, _ -> true }
            val engine = engine(sink = sink, synthesizer = synthesizer)
            assertTrue(engine.initialize() is TtsEngineAvailability.Ready)

            engine.close()
            engine.close()
            val event = engine.speak("closed", UtteranceId("closed")).toList().last()

            assertEquals(TtsEngineEvent.Failed(UtteranceId("closed"), TtsEngineFailureKind.SYNTHESIS_FAILED), event)
            assertEquals(1, sink.closeCount)
            assertEquals(1, synthesizer.closeCount)
        }

    private fun engine(
        locale: Locale = Locale.US,
        voiceId: String? = null,
        modelState: KokoroModelState = installedState(),
        sink: FakeSink =
            FakeSink { _, onAudible ->
                onAudible()
                true
            },
        synthesizer: FakeSynthesizer = FakeSynthesizer(),
        chunker: (String) -> List<KokoroPhonemeChunk> = ::defaultChunks,
    ): KokoroTtsEngine =
        KokoroTtsEngine(
            locale = locale,
            voiceId = voiceId,
            modelStateReader = KokoroModelStateReader { modelState },
            phonemizer = FakePhonemizer(chunker),
            sink = sink,
            synthesizerLoader = { synthesizer },
            dispatcher = Dispatchers.Default,
        )

    private fun defaultChunks(text: String): List<KokoroPhonemeChunk> =
        when (text) {
            "two pieces" -> listOf(KokoroPhonemeChunk("A", "first"), KokoroPhonemeChunk("B", "second"))
            "synth fail" -> listOf(KokoroPhonemeChunk("FAIL", text))
            else -> listOf(KokoroPhonemeChunk("A", text))
        }

    private fun installedState(): KokoroModelState.Installed =
        KokoroModelState.Installed(File("fake-verified-model.onnx"), KokoroArtifact.DEFAULT, KokoroArtifact.DEFAULT.sizeBytes)

    private fun corruptState(): KokoroModelState.Corrupt =
        KokoroModelState.Corrupt(
            reason = "test graph is not verified",
            error =
                com.voicechat.agent.domain.VoiceAgentError(
                    com.voicechat.agent.domain.ErrorCode.MODEL_CORRUPT,
                    "test graph is not verified",
                ),
        )

    private class FakePhonemizer(
        private val chunker: (String) -> List<KokoroPhonemeChunk>,
    ) : KokoroPhonemizer {
        override fun phonemes(text: String): String = text

        override fun chunks(
            text: String,
            maxPhonemes: Int,
        ): List<KokoroPhonemeChunk> = chunker(text)

        override fun encode(phonemes: String): IntArray = intArrayOf(phonemes.firstOrNull()?.code ?: 0)
    }

    private class FakeSynthesizer(
        private val fail: Boolean = false,
    ) : KokoroSynthesizer {
        var synthesisCount = 0
            private set
        var closeCount = 0
            private set

        override fun synthesize(
            ids: IntArray,
            speed: Float,
        ): FloatArray {
            synthesisCount += 1
            if (fail || ids[0] == 'F'.code) throw IllegalStateException("test synthesis failure")
            return floatArrayOf(if (ids[0] == 'B'.code) 2f else 1f)
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeSink(
        private val playBlock: suspend (FloatArray, () -> Unit) -> Boolean,
    ) : KokoroAudioSink,
        KokoroAudioRouteProvider {
        var closeCount = 0
            private set
        var onStop: suspend () -> Unit = {}

        override suspend fun play(
            samples: FloatArray,
            onAudible: () -> Unit,
        ): Boolean = playBlock(samples, onAudible)

        override suspend fun stop() = onStop()

        override fun close() {
            closeCount += 1
        }

        override fun outputRoute(): TtsOutputRoute = TtsOutputRoute.BUILTIN_SPEAKER
    }
}
