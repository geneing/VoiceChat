package com.voicechat.agent.audio

import android.content.Context
import android.media.MediaRecorder
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * App-boundary factory for the real microphone capture path.
 *
 * Keeps the Android construction (permission, `AudioRecord`, route monitor,
 * audio focus) in one place so callers depend only on the [MicrophoneAudioInput]
 * contract. Nothing requests the permission here; the caller requests it at the
 * point of use (see [rememberMicrophonePermissionController]) and then starts
 * capture.
 */
object MicrophoneAudioCapture {
    fun create(
        context: Context,
        config: MicrophoneCaptureConfig = MicrophoneCaptureConfig(),
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        permission: MicrophonePermission? = null,
    ): MicrophoneAudioInput {
        val appContext = context.applicationContext
        return MicrophoneAudioInput(
            permission = permission ?: AndroidMicrophonePermission(appContext),
            recorderFactory =
                AndroidPcmRecorderFactory(
                    context = appContext,
                    format = config.format,
                    audioSource = audioSource,
                    frameSizeSamples = config.frameSizeSamples,
                ),
            config = config,
            routeMonitor = AndroidAudioRouteMonitor(appContext),
            focusController = AndroidAudioFocusController(appContext),
            sourceLabel = audioSourceName(audioSource),
            diagnostics = diagnostics,
            clock = clock,
            dispatcher = dispatcher,
        )
    }
}
