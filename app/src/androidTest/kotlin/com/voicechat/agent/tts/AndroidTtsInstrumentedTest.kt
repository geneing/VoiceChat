package com.voicechat.agent.tts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.UtteranceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * On-device checks for the M11 platform TTS engine (Tests.md).
 *
 * They run only with `:app:connectedDebugAndroidTest`. Voice and availability
 * are read from the real engine and never faked: a "ready" state must be backed
 * by an embedded voice that `getVoices()` actually reported, and the
 * immediate-stop check is skipped (`Assume`) when no on-device voice is
 * installed.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTtsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thePlatformEngineEnumeratesOnlyEmbeddedVoices() {
        runBlocking {
            val engine = AndroidTtsEngine(context, Locale.US)
            try {
                val availability = engine.initialize()
                val voices = engine.installedVoices()
                val onDevice = OnDeviceVoiceSelector.onDeviceVoices(voices)
                Log.i(
                    TAG,
                    "M11 availability=$availability engine=${engine.engineId.value} " +
                        "voices=${voices.size} onDevice=${onDevice.size}",
                )

                // The policy must never expose a network voice as selectable.
                assertTrue("on-device selection included a network voice", onDevice.all { it.isOnDevice })

                when (availability) {
                    is TtsEngineAvailability.Ready -> {
                        assertFalse("a ready voice must be embedded", availability.voice.requiresNetwork)
                        assertTrue(
                            "the selected voice must be one getVoices() reported",
                            onDevice.any { it.id == availability.voice.id },
                        )
                    }

                    is TtsEngineAvailability.NoOnDeviceVoice -> {
                        assertTrue("no on-device voice was reported, so the embedded set must be empty", onDevice.isEmpty())
                    }

                    is TtsEngineAvailability.Unavailable -> {
                        // Recorded above; an unavailable engine is not a pass or a fake.
                        Log.w(TAG, "M11 engine unavailable: ${availability.error.code}")
                    }
                }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun immediateStopEndsSpeechWithoutHanging() {
        runBlocking {
            val engine = AndroidTtsEngine(context, Locale.US)
            try {
                val availability = engine.initialize()
                assumeTrue(
                    "no embedded voice on this device; the stop path needs a ready engine",
                    availability is TtsEngineAvailability.Ready,
                )

                val utteranceId = UtteranceId("instrumented-stop")
                val events = mutableListOf<TtsEngineEvent>()
                val job =
                    launch(Dispatchers.Default) {
                        engine.speak("one two three four five six seven eight nine ten", utteranceId).collect { events += it }
                    }
                // Let playback start, then stop it immediately and require a terminal event.
                delay(400L)
                engine.stop()
                withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { job.join() }

                assertFalse("stop left the speak flow hanging", job.isActive)
                assertTrue(
                    "expected a terminal event, got ${events.map { it::class.simpleName }}",
                    events.any {
                        it is TtsEngineEvent.Completed || it is TtsEngineEvent.Interrupted || it is TtsEngineEvent.Failed
                    },
                )
                Log.i(TAG, "M11 immediate stop events=${events.map { it::class.simpleName }}")
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun theContractAdapterRejectsEmptyInputAndCloses() {
        runBlocking {
            val tts = OnDeviceTts.create(context, Locale.US)
            try {
                // Empty input completes without touching the engine; no voice required.
                assertEquals(emptyList<TtsEvent>(), tts.speak("", UtteranceId("empty")).toList())
            } finally {
                tts.close()
            }
        }
    }

    private companion object {
        const val TAG = "AndroidTtsInstrumented"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
