package com.voicechat.agent.tts

import android.content.Context
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Locale

/**
 * App-boundary factory for the real on-device TTS path.
 *
 * Keeps the Android construction in one place so callers depend only on the M02
 * [TextToSpeech] contract: the platform engine (`AndroidTtsEngine`) is wrapped by
 * the pure [EngineTextToSpeech] adapter, which owns the on-device-only policy and
 * playback accounting. Nothing here requests a permission or a network
 * connection; the platform TTS engine is on-device and network voices are
 * rejected by [OnDeviceVoiceSelector].
 */
object OnDeviceTts {
    /**
     * Builds the platform TTS path. [locale] selects the embedded voice;
     * [voiceId], when non-null, requires that exact installed embedded voice
     * (R-0181). [diagnostics] receives privacy-safe
     * `TTS_SYNTHESIS`/`TTS_PLAYBACK` events.
     */
    fun create(
        context: Context,
        locale: Locale = Locale.getDefault(),
        voiceId: String? = null,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TextToSpeech {
        val engine =
            AndroidTtsEngine(
                context = context.applicationContext,
                locale = locale,
                preferredVoiceId = voiceId,
                dispatcher = dispatcher,
            )
        return EngineTextToSpeech(engine = engine, diagnostics = diagnostics, clock = clock)
    }
}
