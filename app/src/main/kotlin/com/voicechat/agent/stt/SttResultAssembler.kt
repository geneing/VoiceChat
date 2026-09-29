package com.voicechat.agent.stt

import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.VoiceAgentError

/**
 * Vendor-neutral response from an on-device recognizer.
 *
 * The ML Kit adapter translates `SpeechRecognizerResponse` values into these
 * so the session logic stays pure and testable on the JVM, where the real
 * engine cannot run.
 *
 * ML Kit can emit several [Final] segments for one session (the official sample
 * concatenates them until `Completed`), so a final segment is not by itself the
 * end of the turn: [SttResultAssembler] merges segments and emits exactly one
 * final [SttEvent.Result] when the session completes.
 */
sealed interface SttEngineResponse {
    /** A revisable hypothesis for the audio seen so far. */
    data class Partial(
        val text: String,
    ) : SttEngineResponse

    /** A finalized segment of the turn; more segments may follow. */
    data class Final(
        val text: String,
        val confidence: Float? = null,
    ) : SttEngineResponse

    /** The recognizer failed; no further results are expected. */
    data class Failure(
        val kind: SttFailureKind,
        val detail: String? = null,
    ) : SttEngineResponse

    /**
     * A stop-induced, non-error termination.
     *
     * Observed on device: after `stopRecognition()`, ML Kit can emit
     * `ErrorResponse(errorCode = 0)` (the `UNKNOWN` code) immediately before the
     * terminal `CompletedResponse`. That is not a recognition failure and must
     * not mask a good transcript; [SttResultAssembler] treats it as benign.
     */
    data object Stopped : SttEngineResponse

    /** The recognizer ended the session cleanly. */
    data object Completed : SttEngineResponse
}

/** Why recognition ended without a usable final result. */
enum class SttFailureKind {
    /** The engine or model is not available on this device. */
    UNAVAILABLE,

    /** The model exists but is not provisioned (downloadable/downloading/busy). */
    NOT_READY,

    /** Recognition was attempted and failed. */
    RECOGNITION_FAILED,
}

/**
 * Turns a stream of [SttEngineResponse]s into the M02 [SttEvent] contract.
 *
 * Responsibilities:
 * - assign a monotonic [TranscriptRevision] so consumers can drop stale
 *   interim guesses;
 * - emit `isFinal = false` revisions while partial and final segments arrive,
 *   then exactly one `isFinal = true` transcript for the turn;
 * - treat empty input explicitly: a session that ends with no recognized text
 *   still emits one final (empty) transcript rather than silently succeeding;
 * - map failures to typed [VoiceAgentError]s and make the terminal state
 *   sticky, so no event is emitted after a final result or a failure.
 *
 * It never drops a recognized text or a low-confidence value: the raw text and
 * any confidence the engine provides are preserved for user review.
 */
class SttResultAssembler(
    private val languageTag: String? = null,
) {
    private var revision: Int = 0

    @Volatile
    private var finished: Boolean = false

    @Volatile
    private var stopRequested: Boolean = false
    private val finalSegments = mutableListOf<String>()
    private var lastPartialText: String = ""
    private var lastConfidence: Float? = null

    /** True once a terminal result or failure has been emitted. */
    val isFinished: Boolean get() = finished

    /** Consumes one engine response and returns the events it produces. */
    fun onResponse(response: SttEngineResponse): List<SttEvent> =
        when (response) {
            is SttEngineResponse.Partial -> onPartial(response.text)
            is SttEngineResponse.Final -> onFinalSegment(response.text, response.confidence)
            is SttEngineResponse.Failure -> onFailure(response.kind, response.detail)
            SttEngineResponse.Stopped -> onStopped()
            SttEngineResponse.Completed -> finish()
        }

    /**
     * Marks that the audio input ended and reports whether the adapter should ask
     * the engine to stop.
     *
     * ML Kit GenAI Speech Recognition was observed on device **not** to complete
     * its response flow when the audio descriptor reaches EOF; only
     * `stopRecognition()` completes it. So on input end the adapter must stop
     * recognition, and the terminal `CompletedResponse` (or the flow ending) then
     * finalizes the transcript through [onResponse]/[finish]. Returns `true` at
     * most once, and `false` when the session already ended. This method is the
     * pure seam the adapter calls, so the ordering rule is JVM-testable.
     */
    fun onInputEnded(): Boolean {
        if (finished || stopRequested) return false
        stopRequested = true
        return true
    }

    /**
     * Ends the session if it has not already ended, emitting the single final
     * transcript. Call this when the engine's response flow completes without a
     * `Completed` response. Idempotent.
     */
    fun finish(): List<SttEvent> = finalize()

    private fun onPartial(text: String): List<SttEvent> {
        if (finished || text.isBlank()) return emptyList()
        lastPartialText = text
        return listOf(SttEvent.Result(transcript(merge(finalsJoined(), text), isFinal = false)))
    }

    private fun onFinalSegment(
        text: String,
        confidence: Float?,
    ): List<SttEvent> {
        if (finished) return emptyList()
        if (confidence != null) lastConfidence = confidence
        val segment = text.trim()
        if (segment.isNotEmpty()) finalSegments += segment
        return listOf(SttEvent.Result(transcript(finalText(), isFinal = false)))
    }

    private fun onFailure(
        kind: SttFailureKind,
        detail: String?,
    ): List<SttEvent> {
        if (finished) return emptyList()
        finished = true
        return listOf(SttEvent.Failed(errorFor(kind, detail)))
    }

    /**
     * Handles a stop-induced [SttEngineResponse.Stopped].
     *
     * It never masks a transcript: once a stop was requested, or once any text
     * has been assembled, the stop response is ignored and the terminal
     * completion (or [finish]) produces the single final transcript. With no text
     * and no stop request, however, a genuinely unknown engine error is still
     * reported rather than silently swallowed.
     */
    private fun onStopped(): List<SttEvent> {
        if (finished) return emptyList()
        if (stopRequested || hasAssembledText()) return emptyList()
        return onFailure(SttFailureKind.RECOGNITION_FAILED, "recognition stopped with an unknown engine error")
    }

    private fun hasAssembledText(): Boolean = finalSegments.isNotEmpty() || lastPartialText.isNotBlank()

    private fun finalize(): List<SttEvent> {
        if (finished) return emptyList()
        finished = true
        return listOf(
            SttEvent.Result(
                Transcript(
                    text = finalText(),
                    revision = nextRevision(),
                    isFinal = true,
                    languageTag = languageTag,
                    confidence = lastConfidence,
                ),
            ),
        )
    }

    private fun transcript(
        text: String,
        isFinal: Boolean,
    ): Transcript =
        Transcript(
            text = text,
            revision = nextRevision(),
            isFinal = isFinal,
            languageTag = languageTag,
        )

    private fun finalText(): String {
        val finals = finalsJoined()
        return if (finals.isNotEmpty()) finals else lastPartialText.trim()
    }

    private fun finalsJoined(): String = finalSegments.joinToString(separator = " ").trim()

    private fun merge(
        first: String,
        second: String,
    ): String {
        val parts = listOf(first.trim(), second.trim()).filter { it.isNotEmpty() }
        return parts.joinToString(separator = " ")
    }

    private fun nextRevision(): TranscriptRevision = TranscriptRevision(revision++)

    private fun errorFor(
        kind: SttFailureKind,
        detail: String?,
    ): VoiceAgentError =
        when (kind) {
            SttFailureKind.UNAVAILABLE -> VoiceAgentError(ErrorCode.STT_UNAVAILABLE, detail)
            SttFailureKind.NOT_READY -> VoiceAgentError(ErrorCode.STT_MODEL_NOT_READY, detail)
            SttFailureKind.RECOGNITION_FAILED -> VoiceAgentError(ErrorCode.STT_RECOGNITION_FAILED, detail)
        }
}

/**
 * Default confidence below which a final transcript is considered low quality.
 *
 * The selected engine does not currently expose a confidence score, so
 * [Transcript.confidence] is `null` and this check is a no-op there; the value
 * is preserved when an engine does provide one. Orchestration (M21) decides
 * what to do with a low-confidence turn; STT never silently drops it.
 */
const val LOW_CONFIDENCE_THRESHOLD: Float = 0.6f

/** True when a final transcript carries a confidence below [threshold]. */
fun Transcript.isLowConfidence(threshold: Float = LOW_CONFIDENCE_THRESHOLD): Boolean = confidence != null && confidence < threshold
