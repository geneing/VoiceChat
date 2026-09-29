package com.voicechat.agent.contracts

import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow

/**
 * Progress of one text-to-speech utterance.
 *
 * The states let orchestration track [textQueued][Queued.text] versus what was
 * actually audible: [Delivered] means the utterance finished playing, while
 * [Interrupted] reports the shortened [deliveredText].
 */
sealed interface TtsEvent {
    /** Text was accepted for synthesis. */
    data class Queued(
        val utteranceId: UtteranceId,
        val text: String,
    ) : TtsEvent

    /** Audible playback started; the first-audio timing point. */
    data class Started(
        val utteranceId: UtteranceId,
        val text: String,
    ) : TtsEvent

    /** The utterance finished playing in full. */
    data class Delivered(
        val utteranceId: UtteranceId,
        val text: String,
    ) : TtsEvent

    /** Playback stopped early; [deliveredText] was actually audible. */
    data class Interrupted(
        val utteranceId: UtteranceId,
        val deliveredText: String,
    ) : TtsEvent

    /** Synthesis or playback failed. */
    data class Failed(
        val error: VoiceAgentError,
    ) : TtsEvent
}

/**
 * On-device text-to-speech synthesis and playback.
 *
 * **Ownership and lifecycle.** [speak] returns a cold flow for one utterance;
 * collection starts synthesis and playback, and cancelling collection stops
 * that utterance and releases its resources. [stop] interrupts whatever is
 * currently audible and is safe to call from another coroutine during barge-in.
 * [close] releases the engine and is idempotent. Implementations must stay
 * on-device (no network voices) and must report an explicit error rather than
 * silently using a network voice. Empty [speak] input completes without
 * playback.
 */
interface TextToSpeech {
    /** Speaks [text], reporting queued/started/delivered/interrupted progress. */
    fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEvent>

    /** Stops audible playback and cancels queued audio immediately. */
    suspend fun stop()

    /** Releases the TTS engine. Idempotent. */
    suspend fun close()
}
