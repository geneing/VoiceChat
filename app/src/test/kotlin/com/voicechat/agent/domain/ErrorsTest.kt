package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {
    @Test
    fun errorCodesRoundTripThroughTheirStableName() {
        ErrorCode.entries.forEach { code ->
            assertEquals(code, ErrorCode.valueOf(code.name))
        }
    }

    @Test
    fun errorCodeNamesAreStableTokens() {
        assertEquals("LLM_AUTHENTICATION_FAILED", ErrorCode.LLM_AUTHENTICATION_FAILED.name)
        assertEquals("AUDIO_PERMISSION_DENIED", ErrorCode.AUDIO_PERMISSION_DENIED.name)
    }

    @Test
    fun categoriesGroupCodesForTypedHandling() {
        assertEquals(ErrorCategory.LANGUAGE_MODEL, ErrorCode.LLM_TIMEOUT.category)
        assertEquals(ErrorCategory.SPEECH_TO_TEXT, ErrorCode.STT_UNAVAILABLE.category)
        assertEquals(ErrorCategory.PERSISTENCE, ErrorCode.PERSISTENCE_FAILED.category)
        assertEquals(ErrorCategory.LANGUAGE_MODEL, VoiceAgentError(ErrorCode.LLM_TIMEOUT).category)
    }

    @Test
    fun retryableDefaultsFromTheCodeAndCanBeOverridden() {
        assertTrue(VoiceAgentError(ErrorCode.LLM_TIMEOUT).retryable)
        assertFalse(VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED).retryable)
        assertTrue(VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, retryable = true).retryable)
    }

    @Test
    fun equalityIsValueBased() {
        assertEquals(
            VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED, detail = "offline"),
            VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED, detail = "offline"),
        )
        assertNotEquals(
            VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED),
            VoiceAgentError(ErrorCode.LLM_TIMEOUT),
        )
    }

    @Test
    fun exceptionMessageIsTheCodeNameNotTheDetail() {
        val error = VoiceAgentError(code = ErrorCode.LLM_AUTHENTICATION_FAILED, detail = "token rejected")
        val exception = VoiceAgentException(error)

        assertEquals("LLM_AUTHENTICATION_FAILED", exception.message)
        assertFalse(exception.message!!.contains("token rejected"))
        assertEquals(error, exception.error)
    }
}
