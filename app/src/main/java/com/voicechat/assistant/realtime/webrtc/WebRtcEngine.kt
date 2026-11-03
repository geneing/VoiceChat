package com.voicechat.assistant.realtime.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide WebRTC initialisation. Each transport creates its own factory
 * and audio device module so that closing a session releases the microphone.
 */
object WebRtcEngine {

    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
    }

    fun createFactory(context: Context): Pair<PeerConnectionFactory, JavaAudioDeviceModule> {
        val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseLowLatency(true)
            .setSampleRate(48_000)
            .setInputSampleRate(48_000)
            .setOutputSampleRate(48_000)
            .createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
        return factory to audioModule
    }
}
