package com.voicechat.agent.domain

/**
 * Monotonic revision counter for a streaming transcript hypothesis.
 *
 * A recognizer may revise its hypothesis several times before finalizing it.
 * Each update carries the next revision so consumers can drop stale interim
 * guesses and consume only the newest one (see `docs/voice-quality-and-latency.md`,
 * "Recognition quality").
 */
@JvmInline
value class TranscriptRevision(
    val value: Int,
) {
    init {
        require(value >= 0) { "TranscriptRevision must not be negative" }
    }

    /** The next revision after this one. */
    fun next(): TranscriptRevision = TranscriptRevision(value + 1)

    override fun toString(): String = value.toString()
}

/**
 * One recognized text hypothesis for a user turn.
 *
 * [isFinal] distinguishes an interim revision from the finalized transcript:
 * only final text is committed once and sent to the LLM. [languageTag] and
 * [confidence] are optional metadata that engines expose when available.
 */
data class Transcript(
    val text: String,
    val revision: TranscriptRevision,
    val isFinal: Boolean,
    val languageTag: String? = null,
    val confidence: Float? = null,
) {
    init {
        require(confidence == null || confidence in 0f..1f) { "confidence must be within 0..1" }
        require(languageTag == null || languageTag.isNotBlank()) { "languageTag must not be blank when present" }
    }

    companion object {
        /** An interim (revisable) hypothesis. */
        fun interim(
            text: String,
            revision: TranscriptRevision = TranscriptRevision(0),
        ): Transcript = Transcript(text = text, revision = revision, isFinal = false)

        /** A finalized hypothesis; this is the text a turn commits. */
        fun final(
            text: String,
            revision: TranscriptRevision = TranscriptRevision(0),
        ): Transcript = Transcript(text = text, revision = revision, isFinal = true)
    }
}
