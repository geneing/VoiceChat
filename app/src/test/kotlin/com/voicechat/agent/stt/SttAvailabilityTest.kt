package com.voicechat.agent.stt

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
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
    fun theCatalogPrefersAdvancedButNeverMarksItReady() {
        val catalog = SttEngines.catalog(Locale.US)

        assertEquals(listOf(SttMode.ADVANCED, SttMode.BASIC), catalog.map { it.mode })
    }

    @Test
    fun preferredPicksTheFirstReadyEngineInCatalogOrder() {
        val catalog = SttEngines.catalog(Locale.US)

        val advancedReady =
            listOf(
                SttAvailabilityMapper.map(catalog[0], SttFeatureStatus.AVAILABLE),
                SttAvailabilityMapper.map(catalog[1], SttFeatureStatus.AVAILABLE),
            )
        assertEquals(SttMode.ADVANCED, SttEngines.preferred(advancedReady)?.mode)

        val onlyBasicReady =
            listOf(
                SttAvailabilityMapper.map(catalog[0], SttFeatureStatus.UNAVAILABLE),
                SttAvailabilityMapper.map(catalog[1], SttFeatureStatus.AVAILABLE),
            )
        assertEquals(SttMode.BASIC, SttEngines.preferred(onlyBasicReady)?.mode)

        val nothingReady =
            listOf(
                SttAvailabilityMapper.map(catalog[0], SttFeatureStatus.DOWNLOADABLE),
                SttAvailabilityMapper.map(catalog[1], SttFeatureStatus.UNAVAILABLE),
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
