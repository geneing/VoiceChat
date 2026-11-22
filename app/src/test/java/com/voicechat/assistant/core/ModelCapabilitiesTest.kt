package com.voicechat.assistant.core

import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.tutor.ConversationMode
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
        assertEquals(RealtimeDefaults.CLONED_OMNI_VOICE, omni.resolveVoice("Cherry"))
    }

    @Test
    fun `each mode offers only the clones recorded for it`() {
        assertEquals(
            listOf(
                ClonedVoice(RealtimeDefaults.CLONED_OMNI_VOICE, "Herm"),
                ClonedVoice(RealtimeDefaults.CLONED_OMNI_VOICE_ZACH, "Zach"),
                ClonedVoice(RealtimeDefaults.CLONED_OMNI_VOICE_JANICE, "Janice"),
                ClonedVoice(
                    RealtimeDefaults.CLONED_TUTOR_VOICE,
                    "Nacho",
                    ConversationMode.SPANISH_TUTOR,
                ),
                ClonedVoice(
                    RealtimeDefaults.CLONED_TUTOR_VOICE_ALT,
                    "Alejandro",
                    ConversationMode.SPANISH_TUTOR,
                ),
            ),
            omni.clonedVoices,
        )
        assertEquals(
            listOf(
                "Herm" to RealtimeDefaults.CLONED_OMNI_VOICE,
                "Zach" to RealtimeDefaults.CLONED_OMNI_VOICE_ZACH,
                "Janice" to RealtimeDefaults.CLONED_OMNI_VOICE_JANICE,
            ),
            omni.voiceOptions(ConversationMode.ASSISTANT),
        )
        assertEquals(
            listOf(
                "Nacho" to RealtimeDefaults.CLONED_TUTOR_VOICE,
                "Alejandro" to RealtimeDefaults.CLONED_TUTOR_VOICE_ALT,
            ),
            omni.voiceOptions(ConversationMode.SPANISH_TUTOR),
        )
        assertEquals(
            listOf(
                RealtimeDefaults.CLONED_OMNI_VOICE,
                RealtimeDefaults.CLONED_OMNI_VOICE_ZACH,
                RealtimeDefaults.CLONED_OMNI_VOICE_JANICE,
            ),
            omni.allowedVoices(ConversationMode.ASSISTANT),
        )
        assertEquals(
            listOf(
                RealtimeDefaults.CLONED_TUTOR_VOICE,
                RealtimeDefaults.CLONED_TUTOR_VOICE_ALT,
            ),
            omni.allowedVoices(ConversationMode.SPANISH_TUTOR),
        )
        assertEquals("Nacho", omni.labelFor(RealtimeDefaults.CLONED_TUTOR_VOICE))
        assertEquals("Alejandro", omni.labelFor(RealtimeDefaults.CLONED_TUTOR_VOICE_ALT))
        assertEquals(
            RealtimeDefaults.CLONED_OMNI_VOICE,
            omni.defaultVoiceFor(ConversationMode.ASSISTANT),
        )
        assertEquals(
            RealtimeDefaults.CLONED_TUTOR_VOICE,
            omni.defaultVoiceFor(ConversationMode.SPANISH_TUTOR),
        )
        assertEquals(
            RealtimeDefaults.CLONED_TUTOR_VOICE_ALT,
            omni.resolveVoice(
                RealtimeDefaults.CLONED_TUTOR_VOICE_ALT,
                ConversationMode.SPANISH_TUTOR,
            ),
        )
    }

    @Test
    fun `a voice recorded for one mode never reaches the other`() {
        // The tutor's Spanish voices are invisible to the assistant.
        assertFalse(omni.supportsVoice(RealtimeDefaults.CLONED_TUTOR_VOICE))
        assertEquals(
            RealtimeDefaults.CLONED_OMNI_VOICE,
            omni.resolveVoice(RealtimeDefaults.CLONED_TUTOR_VOICE),
        )
        // And the assistant's voices are invisible to the tutor.
        assertFalse(
            omni.supportsVoice(
                RealtimeDefaults.CLONED_OMNI_VOICE,
                ConversationMode.SPANISH_TUTOR,
            ),
        )
        assertEquals(
            RealtimeDefaults.CLONED_TUTOR_VOICE,
            omni.resolveVoice(RealtimeDefaults.CLONED_OMNI_VOICE, ConversationMode.SPANISH_TUTOR),
        )
        // A system voice is offered in neither mode while the account has clones.
        assertFalse(omni.supportsVoice("Tina"))
        assertFalse(omni.supportsVoice("Tina", ConversationMode.SPANISH_TUTOR))
        assertEquals(RealtimeDefaults.CLONED_OMNI_VOICE, omni.resolveVoice("Tina"))
        assertEquals(
            RealtimeDefaults.CLONED_TUTOR_VOICE,
            omni.resolveVoice("Tina", ConversationMode.SPANISH_TUTOR),
        )
    }

    @Test
    fun `an omni clone never reaches the audio model`() {
        assertEquals(emptyList<ClonedVoice>(), audio.clonedVoices)
        assertFalse(audio.supportsVoice(RealtimeDefaults.CLONED_OMNI_VOICE))
        assertFalse(audio.supportsVoice(RealtimeDefaults.CLONED_OMNI_VOICE_ZACH))
        assertFalse(audio.supportsVoice(RealtimeDefaults.CLONED_OMNI_VOICE_JANICE))
        assertFalse(audio.supportsVoice(RealtimeDefaults.CLONED_TUTOR_VOICE))
        assertEquals("longanqian_v3.1", audio.resolveVoice(RealtimeDefaults.CLONED_OMNI_VOICE))
        assertEquals(89, audio.voiceOptions().size)
        assertTrue(audio.voiceOptions().all { (label, id) -> label == id })
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
