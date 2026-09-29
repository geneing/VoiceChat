package com.voicechat.agent.fake

import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.UtteranceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Deterministic [TextToSpeech] that emits scripted events per utterance.
 *
 * The default script is queued -> started -> delivered. Pass a custom [script]
 * to produce an interrupted utterance or a failure. [stopCount] and
 * [cancellationCount] let tests assert barge-in and abandonment behavior.
 */
class FakeTextToSpeech(
    private val script: (UtteranceId, String) -> List<TtsEvent> = { utteranceId, text ->
        listOf(
            TtsEvent.Queued(utteranceId, text),
            TtsEvent.Started(utteranceId, text),
            TtsEvent.Delivered(utteranceId, text),
        )
    },
    private val eventDelayMillis: Long = 0L,
) : TextToSpeech {
    /** Text passed to [speak], in call order. */
    val spokenTexts: MutableList<String> = mutableListOf()

    /** Number of [stop] calls. */
    var stopCount: Int = 0
        private set

    /** Number of speak collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEvent> =
        flow {
            spokenTexts += text
            script(utteranceId, text).forEach { event ->
                if (eventDelayMillis > 0L) delay(eventDelayMillis)
                emit(event)
            }
        }.onCompletion { cause ->
            if (cause is CancellationException) cancellationCount++
        }

    override suspend fun stop() {
        stopCount++
    }

    override suspend fun close() {
        closed = true
    }
}
