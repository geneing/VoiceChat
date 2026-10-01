package com.voicechat.agent.tts

import com.voicechat.agent.fake.ttsVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The on-device-only voice policy (`docs/decisions.md` §2.2). */
class TtsVoiceSelectionTest {
    @Test
    fun networkRequiredVoicesAreExcluded() {
        val voices =
            listOf(
                ttsVoice("on-device-a"),
                ttsVoice("network-b", requiresNetwork = true),
                ttsVoice("on-device-c"),
            )

        val onDevice = OnDeviceVoiceSelector.onDeviceVoices(voices)

        assertEquals(listOf("on-device-a", "on-device-c"), onDevice.map { it.id })
        assertTrue(onDevice.all { it.isOnDevice })
    }

    @Test
    fun selectionNeverReturnsANetworkVoice() {
        val voices = listOf(ttsVoice("network-only", requiresNetwork = true))

        assertNull(OnDeviceVoiceSelector.select(voices, Locale.US))
    }

    @Test
    fun selectionIsNullWhenNoOnDeviceVoiceMatchesTheLanguage() {
        val voices = listOf(ttsVoice("german", language = "de", country = "DE"))

        assertNull(OnDeviceVoiceSelector.select(voices, Locale.US))
    }

    @Test
    fun selectionPrefersAnExactLocaleMatch() {
        val voices =
            listOf(
                ttsVoice("en-gb", language = "en", country = "GB", quality = 500),
                ttsVoice("en-us", language = "en", country = "US", quality = 100),
            )

        assertEquals("en-us", OnDeviceVoiceSelector.select(voices, Locale.US)?.id)
    }

    @Test
    fun selectionPrefersHigherQualityThenLowerLatency() {
        val voices =
            listOf(
                ttsVoice("slow", quality = 500, latency = 900),
                ttsVoice("fast-good", quality = 500, latency = 100),
                ttsVoice("poor", quality = 100, latency = 10),
            )

        assertEquals("fast-good", OnDeviceVoiceSelector.select(voices, Locale.US)?.id)
    }

    @Test
    fun selectPreferredReturnsTheExactInstalledEmbeddedVoice() {
        val voices =
            listOf(
                ttsVoice("voice-a", quality = 100),
                ttsVoice("voice-b", quality = 900),
            )

        assertEquals("voice-a", OnDeviceVoiceSelector.selectPreferred(voices, "voice-a")?.id)
    }

    @Test
    fun selectPreferredNeverReturnsANetworkVoice() {
        val voices = listOf(ttsVoice("cloud", requiresNetwork = true))

        assertNull(OnDeviceVoiceSelector.selectPreferred(voices, "cloud"))
    }

    @Test
    fun selectPreferredIsNullWhenTheVoiceIsNotInstalled() {
        val voices = listOf(ttsVoice("voice-a"))

        assertNull(OnDeviceVoiceSelector.selectPreferred(voices, "voice-missing"))
    }
}
