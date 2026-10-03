package com.voicechat.agent.stt

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The availability mapping is the "never claim ready" rule from M08: only an
 * `AVAILABLE` feature status may produce a ready engine. These are pure JVM
 * tests; they assert the rule, not the device.
 */
class SttAvailabilityTest {
    private val basic = SttEngine(SttMode.BASIC, Locale.US)
    private val advanced = SttEngine(SttMode.ADVANCED, Locale.US)

    @Test
    fun onlyAvailableIsReportedReady() {
        assertTrue(SttAvailabilityMapper.map(basic, SttFeatureStatus.AVAILABLE) is SttAvailability.Ready)
        assertTrue(SttAvailabilityMapper.map(basic, SttFeatureStatus.DOWNLOADABLE) is SttAvailability.DownloadRequired)
        assertTrue(SttAvailabilityMapper.map(basic, SttFeatureStatus.DOWNLOADING) is SttAvailability.Downloading)
        assertTrue(SttAvailabilityMapper.map(basic, SttFeatureStatus.UNAVAILABLE) is SttAvailability.Unavailable)
    }

    @Test
    fun unavailableCarriesATypedReason() {
        val unavailable = SttAvailabilityMapper.map(basic, SttFeatureStatus.UNAVAILABLE) as SttAvailability.Unavailable

        assertEquals(ErrorCode.STT_UNAVAILABLE, unavailable.error.code)
        assertEquals(UnavailableReason.DEVICE_UNSUPPORTED, unavailable.reason)
        assertTrue(!unavailable.error.retryable)
    }

    @Test
    fun theEngineIsSingleAcrossModesButModelsDiffer() {
        assertEquals(SttEngine.ENGINE_ID, basic.engineId)
        assertEquals(SttEngine.ENGINE_ID, advanced.engineId)
        assertTrue(basic.modelId != advanced.modelId)
        assertEquals("mlkit-speech-basic", basic.modelId.value)
        assertEquals("mlkit-speech-advanced", advanced.modelId.value)
    }

    @Test
    fun theCatalogPrefersParakeetEouThenTheMlKitModes() {
        val catalog = SttEngines.catalog(Locale.US)

        assertEquals(listOf(SttMode.PARAKEET_EOU, SttMode.ADVANCED, SttMode.BASIC), catalog.map { it.mode })
        assertEquals(listOf(SttMode.ADVANCED, SttMode.BASIC), SttEngines.mlKitCatalog(Locale.US).map { it.mode })
    }

    @Test
    fun preferredPicksTheFirstReadyEngineInCatalogOrder() {
        val parakeet = SttEngine(SttMode.PARAKEET_EOU, Locale.US)
        val advanced = SttEngine(SttMode.ADVANCED, Locale.US)
        val basic = SttEngine(SttMode.BASIC, Locale.US)

        // Parakeet-EOU is the default once its app-managed model is installed.
        val parakeetReady =
            listOf(
                SttAvailability.Ready(parakeet),
                SttAvailability.Ready(advanced),
                SttAvailability.Ready(basic),
            )
        assertEquals(SttMode.PARAKEET_EOU, SttEngines.preferred(parakeetReady)?.mode)

        // Without it, the first ready ML Kit mode is the fallback.
        val onlyAdvancedReady =
            listOf(
                SttAvailability.DownloadRequired(parakeet),
                SttAvailability.Ready(advanced),
                SttAvailability.Ready(basic),
            )
        assertEquals(SttMode.ADVANCED, SttEngines.preferred(onlyAdvancedReady)?.mode)

        val onlyBasicReady =
            listOf(
                SttAvailability.DownloadRequired(parakeet),
                SttAvailability.DownloadRequired(advanced),
                SttAvailability.Ready(basic),
            )
        assertEquals(SttMode.BASIC, SttEngines.preferred(onlyBasicReady)?.mode)

        val nothingReady =
            listOf(
                SttAvailability.DownloadRequired(parakeet),
                SttAvailability.DownloadRequired(advanced),
                SttAvailability.Unavailable(
                    basic,
                    UnavailableReason.DEVICE_UNSUPPORTED,
                    VoiceAgentError(ErrorCode.STT_UNAVAILABLE, "unavailable"),
                ),
            )
        assertNull(SttEngines.preferred(nothingReady))
    }

    @Test
    fun downloadStatusesAreExplicitAndTypeSafe() {
        assertTrue(SttDownloadStatus.Started(1024L).bytesToDownload == 1024L)
        assertTrue(SttDownloadStatus.Progress(512L).bytesDownloaded == 512L)
        assertEquals(SttDownloadStatus.Completed, SttDownloadStatus.Completed)
    }
}
