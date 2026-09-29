package com.voicechat.agent.audio

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Ties capture teardown to the owner's lifecycle.
 *
 * Registering this makes `ON_STOP` invoke [teardown], which should cancel the
 * capture collection and/or [MicrophoneAudioInput.close] so the recorder and
 * audio focus are released when the dialog leaves the foreground. Unlike a
 * process-scoped resource, this observer holds no reference to the input, so it
 * cannot leak one.
 *
 * Returns the registered observer so the caller can remove it if needed.
 */
fun Lifecycle.cleanupCaptureOnStop(teardown: () -> Unit): DefaultLifecycleObserver {
    val observer =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                teardown()
            }
        }
    addObserver(observer)
    return observer
}
