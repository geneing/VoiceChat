package com.voicechat.agent.stt

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

/**
 * M26/R-0181: a persisted STT mode must be authoritative. When the chosen mode
 * is not ready, selection returns `null` so the loop reports the selected option
 * unavailable instead of silently using the other mode.
 */
class SttEnginesSelectTest {
    private val advanced = SttEngine(SttMode.ADVANCED, Locale.US)
    private val basic = SttEngine(SttMode.BASIC, Locale.US)

    @Test
    fun theChosenModeIsSelectedWhenItIsReady() {
        val statuses = listOf(SttAvailability.Ready(advanced), SttAvailability.Ready(basic))

        assertSame(advanced, SttEngines.select(statuses, SttMode.ADVANCED))
        assertSame(basic, SttEngines.select(statuses, SttMode.BASIC))
    }

    @Test
    fun aChosenModeThatIsNotReadyIsNeverSubstituted() {
        val statuses = listOf(SttAvailability.DownloadRequired(basic), SttAvailability.Ready(advanced))

        assertNull(SttEngines.select(statuses, SttMode.BASIC))
    }

    @Test
    fun noPreferencePrefersTheFirstReadyInCatalogOrder() {
        val statuses = listOf(SttAvailability.DownloadRequired(advanced), SttAvailability.Ready(basic))

        assertSame(basic, SttEngines.select(statuses, mode = null))
    }

    @Test
    fun nothingReadySelectsNothing() {
        val statuses = listOf(SttAvailability.DownloadRequired(advanced), SttAvailability.DownloadRequired(basic))

        assertNull(SttEngines.select(statuses, mode = null))
    }
}
