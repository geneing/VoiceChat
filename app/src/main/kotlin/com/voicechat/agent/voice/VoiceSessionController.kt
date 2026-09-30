package com.voicechat.agent.voice

import com.voicechat.agent.domain.Conversation
import kotlinx.coroutines.flow.StateFlow

/**
 * A running voice session the UI can start and stop (M24).
 *
 * One controller owns one capture session: [run] suspends until capture ends (or
 * [stop] is called) and internally handles logical turns and barge-in. A new
 * controller is created per session by a [VoiceSessionFactory] because platform
 * capture resources are single-use.
 */
interface VoiceSessionController {
    /** Observable session state, for the dialog's voice control. */
    val state: StateFlow<VoiceSessionState>

    /**
     * Runs the session over [conversation] until capture ends or [stop] is
     * called. The coordinator appends voice turns to [conversation] through the
     * M21 orchestrator, so the caller passes the currently open conversation or a
     * fresh empty one.
     */
    suspend fun run(conversation: Conversation)

    /**
     * Stops capture and in-flight work. Idempotent; safe to call from another
     * coroutine. The session still persists whatever was truthfully delivered.
     */
    fun stop()
}

/**
 * Builds a fresh [VoiceSessionController] for one voice session.
 *
 * The app boundary supplies the platform capture/STT/TTS and the resolved
 * provider source; the listener receives the session's progress. Keeping this a
 * factory means a session always starts from clean, single-use resources and no
 * platform type leaks into the coordinator or the UI.
 */
fun interface VoiceSessionFactory {
    /** @return a controller that reports to [listener] and resolves providers through [providerSource]. */
    fun create(
        listener: VoiceSessionListener,
        providerSource: VoiceTurnProviderSource,
    ): VoiceSessionController
}
