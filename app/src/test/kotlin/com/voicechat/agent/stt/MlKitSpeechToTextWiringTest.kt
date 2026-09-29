package com.voicechat.agent.stt

import com.voicechat.agent.contracts.SpeechToText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compile-and-wire check for the real adapter without a device. Construction and
 * [SpeechToText.close] only hold plain state; they never instantiate the ML Kit
 * client, so this does not claim the engine is available on any device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MlKitSpeechToTextWiringTest {
    @Test
    fun reportsTheSingleEngineIdAndClosesIdempotently() =
        runBlocking {
            val speechToText: SpeechToText = MlKitSpeechToText(SttEngine(SttMode.ADVANCED, Locale.US))

            assertEquals(SttEngine.ENGINE_ID, speechToText.engineId)
            assertEquals("mlkit-genai-speech-recognition", speechToText.engineId.value)

            speechToText.close()
            speechToText.close()
            assertTrue(true)
        }
}
