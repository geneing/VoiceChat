package com.voicechat.assistant.realtime.handoff

import kotlinx.coroutines.CompletableDeferred

/**
 * Warms one reasoning call from a hard final voice transcript while the realtime
 * model composes its acknowledgement. If the model then calls `deep_reasoning`
 * for the same question, the answer is already streaming or done, so the spoken
 * reply has no extra pause. A warm call the model never asks for is cancelled at
 * the end of the turn.
 *
 * The tokens a discarded warm call spent are still billed; the caller reports
 * them through [com.voicechat.assistant.realtime.SessionListener.onUsage].
 */
class SpeculativeHandoff {

    private data class Warm(
        val transcript: String,
        val deferred: CompletableDeferred<Result<ReasoningResult>>,
    )

    private var warm: Warm? = null

    val running: Boolean get() = warm != null

    /**
     * Claims the single warm slot for [transcript]. Returns where the result
     * lands, or `null` when a call is already warm or the text is unusable.
     */
    fun start(transcript: String): CompletableDeferred<Result<ReasoningResult>>? {
        if (warm != null) return null
        val text = transcript.trim()
        if (text.isEmpty()) return null
        val deferred = CompletableDeferred<Result<ReasoningResult>>()
        warm = Warm(text, deferred)
        return deferred
    }

    /**
     * Hands the warm call to a `deep_reasoning` request when it answers the same
     * question, otherwise drops it: an answer to a different task must never be
     * delivered.
     */
    fun take(task: String): WarmTake {
        val current = warm ?: return WarmTake.None
        warm = null
        if (matches(current.transcript, task)) return WarmTake.Reuse(current.deferred)
        current.deferred.cancel()
        return WarmTake.Mismatch
    }

    /** Stops the warm call, if any, without waiting for it. */
    fun cancel() {
        val current = warm ?: return
        warm = null
        current.deferred.cancel()
    }

    sealed class WarmTake {
        /** A warm answer for this task is on its way (or already there). */
        class Reuse(val deferred: CompletableDeferred<Result<ReasoningResult>>) : WarmTake()

        /** A warm call existed but answered a different question; it is dropped. */
        data object Mismatch : WarmTake()

        /** Nothing was warm. */
        data object None : WarmTake()
    }

    companion object {

        /** Shared-word ratio that counts as the same question. */
        const val MIN_OVERLAP = 0.5

        private val WORD = Regex("[a-z0-9]{3,}")

        private val STOP_WORDS = setOf(
            "the", "and", "for", "you", "your", "what", "how", "why", "does", "did",
            "can", "could", "would", "should", "with", "that", "this", "from", "about",
            "tell", "give", "please", "help", "explain", "make", "need", "want",
            "question", "answer", "task", "user",
        )

        fun matches(transcript: String, task: String): Boolean {
            val a = transcript.trim().lowercase()
            val b = task.trim().lowercase()
            if (a.isEmpty() || b.isEmpty()) return false
            if (a == b || a.startsWith(b) || b.startsWith(a)) return true
            return overlap(a, b) >= MIN_OVERLAP
        }

        /** Jaccard similarity over the meaningful words of both texts. */
        fun overlap(left: String, right: String): Double {
            val a = words(left)
            val b = words(right)
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            val shared = a.count { it in b }
            val total = a.size + b.size - shared
            return if (total <= 0) 0.0 else shared.toDouble() / total
        }

        private fun words(text: String): Set<String> =
            WORD.findAll(text.lowercase())
                .map { it.value }
                .filter { it !in STOP_WORDS }
                .toSet()
    }
}
