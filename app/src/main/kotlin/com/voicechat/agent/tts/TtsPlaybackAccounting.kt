package com.voicechat.agent.tts

import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.UtteranceId

/**
 * Truthful ledger of assistant text against TTS playback, aligned with
 * [AssistantDelivery].
 *
 * Orchestration feeds one [TtsEvent] at a time, associated with the utterance it
 * belongs to (the M02 [TtsEvent.Failed] carries no utterance id). The ledger
 * distinguishes what was **generated**, **queued**, **started**, and **audibly
 * completed**, so an interruption cannot leave a queued chunk unaccounted: every
 * non-empty queued utterance reaches a `Delivered`, `Interrupted`, or `Failed`
 * terminal and [isFullyAccounted] becomes true.
 *
 * Chunk order is the order utterances were first queued, which is the order the
 * assistant text was produced, so the concatenated [deliveredText] is a prefix
 * of the generated text. [toDelivery] clamps it to the supplied generated text
 * so the [AssistantDelivery] invariant always holds.
 */
class TtsPlaybackAccounting {
    private class Entry(
        val text: String,
    ) {
        var started: Boolean = false
        var deliveredText: String? = null
        var terminal: Terminal? = null
    }

    private enum class Terminal {
        DELIVERED,
        INTERRUPTED,
        FAILED,
    }

    private val entries = LinkedHashMap<UtteranceId, Entry>()
    private val generated = StringBuilder()

    /** Assistant text generated for the turn, in the order it was reported. */
    val generatedText: String get() = generated.toString()

    /** Records assistant text generated for the turn (it may exceed what was queued). */
    fun onGenerated(text: String) {
        generated.append(text)
    }

    /** Applies one contract [event] belonging to [utteranceId]. */
    fun onEvent(
        utteranceId: UtteranceId,
        event: TtsEvent,
    ) {
        when (event) {
            is TtsEvent.Queued -> {
                entries.getOrPut(utteranceId) { Entry(event.text) }
            }

            is TtsEvent.Started -> {
                entry(utteranceId, event.text).started = true
            }

            is TtsEvent.Delivered -> {
                entry(utteranceId, event.text).apply {
                    started = true
                    deliveredText = event.text
                    terminal = Terminal.DELIVERED
                }
            }

            is TtsEvent.Interrupted -> {
                entry(utteranceId, "").apply {
                    deliveredText = event.deliveredText
                    terminal = Terminal.INTERRUPTED
                }
            }

            is TtsEvent.Failed -> {
                entry(utteranceId, "").apply {
                    deliveredText = ""
                    terminal = Terminal.FAILED
                }
            }
        }
    }

    /** Concatenation of accepted chunk texts, in queue order. */
    val queuedText: String get() = entries.values.joinToString(separator = "") { it.text }

    /** Concatenation of chunks whose playback started, in queue order. */
    val startedText: String
        get() = entries.values.filter { it.started }.joinToString(separator = "") { it.text }

    /** Concatenation of audible prefixes, in queue order; a prefix of [queuedText]. */
    val deliveredText: String get() = entries.values.joinToString(separator = "") { it.deliveredText ?: "" }

    /** Assistant characters generated for the turn. */
    val generatedCharacterCount: Int get() = generated.length

    /** Assistant characters accepted for synthesis. */
    val queuedCharacterCount: Int get() = entries.values.sumOf { it.text.length }

    /** Assistant characters whose audible playback started. */
    val startedCharacterCount: Int get() = entries.values.filter { it.started }.sumOf { it.text.length }

    /** Assistant characters confirmed audible. */
    val deliveredCharacterCount: Int get() = deliveredText.length

    /** True when every queued utterance reached a terminal accounting. */
    val isFullyAccounted: Boolean get() = entries.values.all { it.terminal != null }

    /** True when at least one queued utterance has no terminal event yet. */
    val hasUnaccounted: Boolean get() = !isFullyAccounted

    /** Aggregate delivery state for the turn. */
    val state: DeliveryState
        get() {
            if (entries.isEmpty()) return DeliveryState.NOT_STARTED
            val terminals = entries.values.mapNotNull { it.terminal }
            return when {
                terminals.any { it == Terminal.FAILED } -> DeliveryState.FAILED
                terminals.any { it == Terminal.INTERRUPTED } -> DeliveryState.INTERRUPTED
                terminals.size < entries.size -> DeliveryState.SPEAKING
                else -> DeliveryState.COMPLETED
            }
        }

    /** The delivery for the accumulated generated text, clamped to a valid prefix. */
    fun toDelivery(): AssistantDelivery = toDelivery(generatedText)

    /** The delivery for [generatedText], clamped to a valid prefix. */
    fun toDelivery(generatedText: String): AssistantDelivery =
        AssistantDelivery(
            deliveredText = longestCommonPrefix(generatedText, deliveredText),
            state = state,
        )

    private fun entry(
        utteranceId: UtteranceId,
        fallbackText: String,
    ): Entry = entries.getOrPut(utteranceId) { Entry(fallbackText) }

    private fun longestCommonPrefix(
        a: String,
        b: String,
    ): String {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return a.substring(0, index)
    }
}
