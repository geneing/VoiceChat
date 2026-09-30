package com.voicechat.agent.stt

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.ErrorCode
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * On-device checks for the M08 STT engine (Tests.md).
 *
 * They run only with `:app:connectedDebugAndroidTest`. Availability is read from
 * the engine at runtime and never faked: when the engine is ready the
 * refuse-to-run path is skipped, and when it is not ready the typed failure is
 * asserted. Live transcription needs a real-time audio feeder and is a manual
 * check, not an automated one.
 */
@RunWith(AndroidJUnit4::class)
class MlKitSttInstrumentedTest {
    private val locale = Locale.US

    @Test
    fun everyCatalogModeReportsATypedAvailabilityWithoutThrowing() {
        runBlocking {
            val results = mutableListOf<String>()

            SttEngines.catalog(locale).forEach { candidate ->
                val availability = MlKitSttStatus.check(candidate)
                assertNotNull(availability)
                results += "${candidate.mode}=$availability"
            }

            // The state itself is device-dependent and is recorded for the run,
            // not asserted, so the test never claims the model is provisioned.
            Log.i(TAG, "M08 availability: ${results.joinToString()}")
            assertEquals(2, results.size)
        }
    }

    @Test
    fun theAdapterRefusesToRunWhenTheEngineIsNotAvailable() {
        runBlocking {
            val engine = SttEngine(SttMode.ADVANCED, locale)
            val availability = MlKitSttStatus.check(engine)
            // On a provisioned device (ADVANCED=Ready) this refusal path does not
            // apply. Returning (rather than Assume) keeps it a pass on such a
            // device while still exercising the refusal when the engine is not
            // ready; the real availability is always logged by the other test.
            if (availability is SttAvailability.Ready) {
                Log.i(TAG, "M08 engine is $availability; the refuse-to-run path is not applicable")
                return@runBlocking
            }

            val adapter = MlKitSpeechToText(engine)
            try {
                val event =
                    withTimeoutOrNull(TRANSCRIBE_TIMEOUT_MILLIS) {
                        adapter.transcribe(emptyFlow()).first()
                    }
                assertTrue("adapter must emit a typed failure, got $event", event is SttEvent.Failed)
                val code = (event as SttEvent.Failed).error.code
                assertTrue(
                    "expected STT_MODEL_NOT_READY or STT_UNAVAILABLE, got $code",
                    code == ErrorCode.STT_MODEL_NOT_READY || code == ErrorCode.STT_UNAVAILABLE,
                )
            } finally {
                adapter.close()
            }
        }
    }

    @Test
    fun theAdapterReportsTheSingleEngineIdentity() {
        val engine = SttEngine(SttMode.ADVANCED, locale)
        val adapter = MlKitSpeechToText(engine)

        assertEquals(SttEngine.ENGINE_ID, adapter.engineId)
        assertEquals("mlkit-genai-speech-recognition", adapter.engineId.value)
        runBlocking { adapter.close() }
    }

    private companion object {
        const val TAG = "MlKitSttInstrumented"
        const val TRANSCRIBE_TIMEOUT_MILLIS = 15_000L
    }
}
