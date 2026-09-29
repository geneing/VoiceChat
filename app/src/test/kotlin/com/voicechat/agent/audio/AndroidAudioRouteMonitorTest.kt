package com.voicechat.agent.audio

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric smoke tests for the platform route monitor: it must degrade
 * gracefully and always report a route, so diagnostics never fail a capture
 * session just because the routing query did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAudioRouteMonitorTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun currentAlwaysReportsARoute() {
        val monitor = AndroidAudioRouteMonitor(application)

        assertNotNull(monitor.current())
    }

    @Test
    fun routesEmitsTheInitialRoute() =
        runBlocking {
            val monitor = AndroidAudioRouteMonitor(application)

            val first = withTimeout(2_000L) { monitor.routes().first() }

            assertNotNull(first)
        }
}
