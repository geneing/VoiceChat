package com.voicechat.agent.stt

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * JVM coverage of [AndroidSpeechRecognizerSpeechToText]'s non-pure lifecycle: the
 * session serialization that the platform recognizer requires, the answer to the
 * engine's end-of-speech, the stop/timeout ordering, and the diagnostics.
 *
 * The platform `SpeechRecognizer` is replaced by [FakeRecognizer] through the
 * [RecognizerProvider] seam, so the riskiest logic runs on CI without a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSpeechRecognizerSpeechToTextTest {
    private val engine = SttEngine(SttMode.ANDROID_SPEECH, Locale.US)
    private val clock = MonotonicClock { 0L }

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun twoConcurrentSessionsSerializeOnThePlatformRecognizer() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val provider = FakeRecognizerProvider()
            val adapter = adapter(provider)

            // Turn N keeps its session open (its input never completes).
            val first = launch { adapter.transcribe(MutableSharedFlow<AudioFrame>()).collect {} }
            runCurrent()
            assertEquals(1, provider.recognizers.size)

            // Turn N+1 starts while turn N is still finalizing: the second session
            // must wait rather than collide with ERROR_RECOGNIZER_BUSY.
            val second = launch { adapter.transcribe(MutableSharedFlow<AudioFrame>()).collect {} }
            runCurrent()
            assertEquals("the second session must wait for the first", 1, provider.recognizers.size)

            // Turn N's engine delivers its final result; only now may N+1 start.
            provider.recognizers[0].results("first")
            runCurrent()
            assertEquals(2, provider.recognizers.size)

            second.cancel()
            first.cancel()
        }

    @Test
    fun endOfSpeechForwardsOneEventWithoutStoppingMidCallback() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val provider = FakeRecognizerProvider()
            val adapter = adapter(provider)
            val events = mutableListOf<SttEvent>()
            val job = launch { adapter.transcribe(MutableSharedFlow<AudioFrame>()).collect { events += it } }
            runCurrent()
            val recognizer = provider.recognizers.single()

            // A re-entrant stop from the callback made the on-device recognizer
            // answer ERROR_CLIENT, so end-of-speech must not stop the session; it
            // only forwards one event, however many times it fires.
            repeat(3) { recognizer.endOfSpeech() }
            runCurrent()

            assertEquals(0, recognizer.stopCount)
            assertEquals(1, events.count { it == SttEvent.EndOfSpeech })

            recognizer.results("hello")
            runCurrent()
            assertEquals("hello", (events.last() as SttEvent.Result).transcript.text)
            job.cancel()
        }

    @Test
    fun inputEndEndsTheAudioAndTheEngineFinalizesOnItsOwn() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val provider = FakeRecognizerProvider()
            val adapter = adapter(provider)
            val events = mutableListOf<SttEvent>()
            // A finite input: the drain completes as soon as the frame is consumed.
            val audio: Flow<AudioFrame> = flow { emit(AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(320), 0L)) }
            val job = launch { adapter.transcribe(audio).collect { events += it } }
            runCurrent()

            val recognizer = provider.recognizers.single()
            // On API 33+ the session ends by closing the custom audio, not by a
            // re-entrant stop (which made the on-device recognizer error).
            assertEquals(0, recognizer.stopCount)
            assertTrue(events.none { it is SttEvent.Result && it.transcript.isFinal })

            recognizer.results("world")
            runCurrent()
            assertEquals("world", (events.last() as SttEvent.Result).transcript.text)
            assertTrue((events.last() as SttEvent.Result).transcript.isFinal)
            job.cancel()
        }

    @Test
    fun noMatchBecomesAnEmptyFinalRatherThanAFailure() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val provider = FakeRecognizerProvider()
            val adapter = adapter(provider)
            val events = mutableListOf<SttEvent>()
            val job = launch { adapter.transcribe(MutableSharedFlow<AudioFrame>()).collect { events += it } }
            runCurrent()

            provider.recognizers.single().error(SpeechRecognizer.ERROR_NO_MATCH)
            runCurrent()

            val final = events.last() as SttEvent.Result
            assertTrue(final.transcript.isFinal)
            assertEquals("", final.transcript.text)
            assertTrue("no-match is a no-speech turn, not a failure", events.none { it is SttEvent.Failed })
            job.cancel()
        }

    @Test
    fun theDiagnosticCarriesTheRecognizerMode() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val provider = FakeRecognizerProvider(onDevice = true)
            val diagnostics = RecordingDiagnosticsSink()
            val adapter = adapter(provider, diagnostics)
            val job = launch { adapter.transcribe(MutableSharedFlow<AudioFrame>()).collect {} }
            runCurrent()
            provider.recognizers.single().results("done")
            runCurrent()

            val mode = diagnostics.events.mapNotNull { it.attributes[DiagnosticAttribute.RECOGNIZER_MODE] }
            assertTrue("the recognizer mode must be recorded", mode.contains("on_device"))
            job.cancel()
        }

    private fun adapter(
        provider: RecognizerProvider,
        diagnostics: com.voicechat.agent.contracts.DiagnosticsSink = com.voicechat.agent.contracts.NoOpDiagnosticsSink,
    ): AndroidSpeechRecognizerSpeechToText =
        AndroidSpeechRecognizerSpeechToText(
            context = RuntimeEnvironment.getApplication(),
            engine = engine,
            diagnostics = diagnostics,
            clock = clock,
            recognizerProvider = provider,
        )

    private class FakeRecognizerProvider(
        private val onDevice: Boolean = true,
    ) : RecognizerProvider {
        val recognizers = mutableListOf<FakeRecognizer>()

        override fun isOnDeviceAvailable(): Boolean = onDevice

        override fun create(onDevice: Boolean): PlatformRecognizer = FakeRecognizer().also { recognizers += it }
    }

    /** A hand-driven stand-in for the platform recognizer. */
    private class FakeRecognizer : PlatformRecognizer {
        private var listener: RecognitionListener? = null
        val intents = mutableListOf<Intent>()
        var stopCount = 0
            private set
        var destroyCount = 0
            private set

        override fun setListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(intent: Intent) {
            intents += intent
        }

        override fun stopListening() {
            stopCount++
        }

        override fun cancel() = Unit

        override fun destroy() {
            destroyCount++
        }

        fun partial(text: String) = requireListener().onPartialResults(bundle(text))

        fun results(text: String) = requireListener().onResults(bundle(text))

        fun error(code: Int) = requireListener().onError(code)

        fun endOfSpeech() = requireListener().onEndOfSpeech()

        private fun requireListener(): RecognitionListener = checkNotNull(listener) { "no listener set" }

        private fun bundle(text: String): Bundle =
            Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }
    }
}
