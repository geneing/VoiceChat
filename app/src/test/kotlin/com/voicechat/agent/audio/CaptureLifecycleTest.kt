package com.voicechat.agent.audio

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric test for the lifecycle cleanup helper: capture teardown must run
 * when the owner stops, not while it is still visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureLifecycleTest {
    @Test
    fun teardownRunsOnStopAndNotOnStart() {
        val owner = TestLifecycleOwner()
        var teardowns = 0
        owner.lifecycle.cleanupCaptureOnStop { teardowns++ }

        owner.start()
        assertEquals(0, teardowns)

        owner.stop()
        assertEquals(1, teardowns)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle get() = registry

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        fun stop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
}
