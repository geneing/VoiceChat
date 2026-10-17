package com.voicechat.agent.contracts

import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow

/**
 * Result of on-device speech recognition.
 *
 * Interim and final hypotheses are both [Result] values distinguished by
 * [Transcript.isFinal]; revisions let consumers discard stale guesses. STT
 * events carry no turn ID: orchestration binds them to the active turn, which
 * keeps the recognizer independent of conversation state.
 */
sealed interface SttEvent {
    /** A new transcript hypothesis. */
    data class Result(
        val transcript: Transcript,
    ) : SttEvent

    /**
     * The recognizer's own end-of-speech detector fired.
     *
     * The platform `SpeechRecognizer` emits this when it believes the speaker
     * stopped. It is a **corroborating** signal only: the bounded VAD/Smart Turn
     * endpoint remains the authority for ending a logical turn, so an early or
     * late engine signal can neither truncate a still-speaking user nor keep a
     * finished turn open. Consumers record it for timing and diagnostics.
     */
    data object EndOfSpeech : SttEvent

    /** Recognition failed; no further results are expected. */
    data class Failed(
        val error: VoiceAgentError,
    ) : SttEvent
}

/**
 * On-device speech-to-text, replaceable without changing orchestration.
 *
 * **Ownership and lifecycle.** [transcribe] takes ownership of its input flow
 * and returns a cold result flow for one recognition session. The returned flow
 * completes after a final [SttEvent.Result] or a [SttEvent.Failed]. Cancelling
 * collection cancels recognition and releases recognizer resources; no events
 * may be emitted after cancellation. Implementations must not send microphone
 * audio off-device and must run recognition off the main thread.
 */
interface SpeechToText {
    /** Identifies the selected on-device engine for diagnostics and settings. */
    val engineId: EngineId

    /** Transcribes [audio], emitting interim then final hypotheses. */
    fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent>

    /** Releases recognizer resources. Idempotent. */
    suspend fun close()
}
