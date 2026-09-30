package com.voicechat.agent.tts

import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.UtteranceId
import kotlinx.coroutines.flow.Flow

/** Why the engine could not finish an enqueued utterance. */
enum class TtsEngineFailureKind {
    /** The engine rejected or failed to generate audio for the text. */
    SYNTHESIS_FAILED,

    /** Audio was synthesized but playback failed. */
    PLAYBACK_FAILED,
}

/**
 * Progress the engine reports for one enqueued utterance.
 *
 * This is the vendor-neutral seam: `android.speech.tts` types never appear here,
 * so the adapter that maps these onto the M02 [com.voicechat.agent.contracts.TtsEvent]
 * contract is pure Kotlin and JVM-testable with a fake engine.
 */
sealed interface TtsEngineEvent {
    /** The utterance this event belongs to. */
    val utteranceId: UtteranceId

    /** Audible playback began; [route] is the output route kind at that moment. */
    data class Started(
        override val utteranceId: UtteranceId,
        val route: TtsOutputRoute? = null,
    ) : TtsEngineEvent

    /** The utterance finished playing in full. */
    data class Completed(
        override val utteranceId: UtteranceId,
    ) : TtsEngineEvent

    /**
     * Playback stopped early; [deliveredText] is the prefix the engine could
     * confirm was audible. It is empty when the engine cannot report progress.
     */
    data class Interrupted(
        override val utteranceId: UtteranceId,
        val deliveredText: String,
    ) : TtsEngineEvent

    /** The engine rejected or failed the utterance; [kind] is the stable reason. */
    data class Failed(
        override val utteranceId: UtteranceId,
        val kind: TtsEngineFailureKind,
    ) : TtsEngineEvent
}

/**
 * Replaceable seam over one on-device text-to-speech engine.
 *
 * `android.speech.tts.TextToSpeech` is the M00-selected engine
 * (`docs/decisions.md` §2.2`); its adapter implements this interface and keeps
 * every platform type inside the Android file. The seam exists so
 * [EngineTextToSpeech] — the `TextToSpeech` contract implementation that owns
 * the queued/started/delivered mapping and accounting — is pure Kotlin and can
 * be tested on the JVM with a fake engine.
 *
 * **Ownership and lifecycle.** [initialize] discovers installed voices and
 * selects an embedded one; it is safe to call more than once. [speak] is a cold
 * per-utterance flow: collecting it enqueues the text and emits events until a
 * terminal [TtsEngineEvent], and cancelling collection stops just that
 * utterance. [stop] interrupts everything queued and audible immediately and is
 * safe to call from another coroutine during barge-in; every in-flight [speak]
 * flow ends with [TtsEngineEvent.Interrupted]. [close] is idempotent.
 * Implementations must stay on-device and never select a network-required voice.
 */
interface TtsEngine {
    /** Stable identity of the engine, for diagnostics and settings. */
    val engineId: EngineId

    /** Discovers voices and selects an embedded voice for the engine locale. */
    suspend fun initialize(): TtsEngineAvailability

    /** Every voice the engine reports, including network-required ones. */
    fun installedVoices(): List<TtsVoice>

    /** Selects a previously discovered voice; a no-op if it is not installed. */
    suspend fun selectVoice(voice: TtsVoice)

    /**
     * Enqueues [text] for playback and reports its progress.
     *
     * The returned flow is cold: collection starts synthesis. It emits
     * `Started`/`Completed`/`Interrupted`/`Failed` for this utterance and
     * completes at a terminal event. Cancelling collection cancels only this
     * utterance.
     */
    fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEngineEvent>

    /** Stops audible playback and cancels queued audio immediately. */
    suspend fun stop()

    /** Releases the engine. Idempotent. */
    suspend fun close()
}
