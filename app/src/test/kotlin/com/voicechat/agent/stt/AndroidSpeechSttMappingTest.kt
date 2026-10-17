package com.voicechat.agent.stt

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The platform `SpeechRecognizer` error mapping is the M27 rule for turning a
 * stable `ERROR_*` code into a vendor-neutral response, so it is asserted on the
 * JVM. No device and no microphone are involved: only the integer codes
 * (compile-time constants) are inspected.
 */
class AndroidSpeechSttMappingTest {
    @Test
    fun noMatchAndSpeechTimeoutAreBenignCompletions() {
        assertEquals(
            SttEngineResponse.Completed,
            AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_NO_MATCH),
        )
        assertEquals(
            SttEngineResponse.Completed,
            AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun busyIsNotReady() {
        val response = AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)

        assertEquals(SttEngineResponse.Failure(SttFailureKind.NOT_READY, "RECOGNIZER_BUSY"), response)
    }

    @Test
    fun missingPermissionIsUnavailable() {
        val response = AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        assertEquals(SttFailureKind.UNAVAILABLE, (response as SttEngineResponse.Failure).kind)
    }

    @Test
    fun unsupportedLanguageIsUnavailable() {
        val response = AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertEquals(SttFailureKind.UNAVAILABLE, (response as SttEngineResponse.Failure).kind)
    }

    @Test
    fun clientAndNetworkErrorsAreRecognitionFailures() {
        val client = AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_CLIENT)
        val network = AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_NETWORK)

        assertEquals(SttFailureKind.RECOGNITION_FAILED, (client as SttEngineResponse.Failure).kind)
        assertEquals(SttFailureKind.RECOGNITION_FAILED, (network as SttEngineResponse.Failure).kind)
    }

    @Test
    fun theFailureDetailCarriesOnlyAStableCode() {
        val response = AndroidSpeechSttMapping.toEngineResponse(SpeechRecognizer.ERROR_CLIENT) as SttEngineResponse.Failure

        assertEquals("ASR_ERROR_${SpeechRecognizer.ERROR_CLIENT}", response.detail)
    }

    @Test
    fun modeLabelNamesHowRecognitionWasRequested() {
        assertEquals("on_device", AndroidSpeechSttMapping.modeLabel(onDevice = true))
        assertEquals("prefer_offline", AndroidSpeechSttMapping.modeLabel(onDevice = false))
    }
}
