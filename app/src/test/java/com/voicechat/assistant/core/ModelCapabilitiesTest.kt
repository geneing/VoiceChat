package com.voicechat.assistant.core

import com.voicechat.assistant.data.model.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilitiesTest {

    private val omni = RealtimeDefaults.models.first { it.id == RealtimeDefaults.OMNI_MODEL }
    private val audio = RealtimeDefaults.models.first { it.id == RealtimeDefaults.AUDIO_MODEL }

    @Test
    fun `audio model is websocket only`() {
        assertEquals(setOf(TransportKind.WEBSOCKET), audio.supportedTransports)
        assertTrue(audio.supportsTools)
        assertEquals("smart_turn_v2", audio.defaultTurnDetection)
        assertTrue(audio.turnDetectionOptions.contains("smart_turn_v2"))
    }

    @Test
    fun `omni supports every transport`() {
        assertTrue(omni.supportedTransports.containsAll(TransportKind.entries))
        assertTrue(omni.supportsTools)
    }

    @Test
    fun `audio model exposes the 3_1 voice list only`() {
        assertEquals(89, audio.voices.size)
        assertTrue(audio.voices.contains("longanqian_v3.1"))
        assertFalse(audio.voices.contains("longanlingxin"))
        assertFalse(audio.voices.contains("longanqian"))
        assertEquals("longanqian_v3.1", audio.resolveVoice("longanlingxin"))
        assertEquals("longanqian_v3.1", audio.defaultVoice)
    }

    @Test
    fun `omni model exposes the 3_8 voice list`() {
        assertEquals(56, omni.voices.size)
        assertTrue(omni.voices.contains("Tina"))
        assertTrue(omni.voices.contains("longanlingxin"))
        assertTrue(omni.voices.contains("Liora Mira"))
        assertFalse(omni.voices.contains("Cherry"))
        assertEquals("Tina", omni.resolveVoice("Cherry"))
    }

    @Test
    fun `cloned voices with the model prefix are accepted`() {
        val clone = "${audio.id}-myvoice-123456"
        assertTrue(audio.supportsVoice(clone))
        assertEquals(clone, audio.resolveVoice(clone))
        assertFalse(audio.supportsVoice("not-a-voice"))
    }

    @Test
    fun `only the audio model accepts tools next to server side search`() {
        assertFalse(omni.supportsToolsWithSearch)
        assertTrue(audio.supportsToolsWithSearch)
    }

    @Test
    fun `client tools win when the model cannot combine them with search`() {
        assertFalse(omni.resolveNativeSearch(requested = true, clientToolsEnabled = true))
        assertTrue(omni.resolveNativeSearch(requested = true, clientToolsEnabled = false))
        assertFalse(omni.resolveNativeSearch(requested = false, clientToolsEnabled = true))
        assertTrue(audio.resolveNativeSearch(requested = true, clientToolsEnabled = true))
        assertFalse(audio.resolveNativeSearch(requested = false, clientToolsEnabled = true))
    }
}
