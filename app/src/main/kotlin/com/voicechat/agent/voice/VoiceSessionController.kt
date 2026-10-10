package com.voicechat.agent.voice

import com.voicechat.agent.domain.Conversation
import kotlinx.coroutines.flow.StateFlow

/**
 * A running voice session the UI can start and stop (M24).
 *
 * One controller owns one capture session: [run] suspends until capture ends (or
 * [stop] is called) and internally handles recognition turns. A new controller
 * is created per session by a [VoiceSessionFactory] because platform capture
 * resources are single-use.
 */
interface VoiceSessionController {
    /** Observable session state, for the dialog's voice control. */
    val state: StateFlow<VoiceSessionState>

    /**
     * Runs the session over [conversation] until capture ends or [stop] is
     * called. Finalized STT transcripts are appended to [conversation] and
     * persisted; this session does not generate assistant responses.
     */
    suspend fun run(conversation: Conversation)

    /**
     * Stops capture and in-flight recognition. Idempotent; safe to call from
     * another coroutine.
     */
    fun stop()
}

/**
 * Builds a fresh [VoiceSessionController] for one voice session.
 *
 * The app boundary supplies platform capture/STT and the endpoint detector; the
 * listener receives recognition progress. Keeping this a factory means each
 * session starts with clean, single-use resources and no platform type leaks
 * into the coordinator or the UI.
 */
fun interface VoiceSessionFactory {
    /** @return a controller that reports session progress to [listener]. */
    fun create(listener: VoiceSessionListener): VoiceSessionController
}
