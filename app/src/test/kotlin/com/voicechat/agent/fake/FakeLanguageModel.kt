package com.voicechat.agent.fake

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.isLegalStreamTransition
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Small deterministic [LanguageModel] that replays one scripted stream
 * verbatim; use [DeterministicLanguageModel] when a test needs stalls,
 * backpressure, capability preflight, or a hot stream that can be cancelled
 * before collection.
 *
 * The script is validated at construction and on emission, so a test cannot
 * assert against a stream no real adapter could produce: a terminal event ends
 * the script and nothing may follow it.
 */
class FakeLanguageModel(
    override val providerId: ProviderId = ProviderId("fake-provider"),
    override val capabilities: LlmCapabilities =
        LlmCapabilities(
            usageReporting = true,
            reasoningLevels = ReasoningLevel.entries.toSet(),
        ),
    private val script: List<LlmStreamEvent> = listOf(LlmStreamEvent.Completed()),
    private val eventDelayMillis: Long = 0L,
) : LanguageModel {
    init {
        require(eventDelayMillis >= 0L) { "eventDelayMillis must not be negative" }
        var previous: LlmStreamEvent? = null
        script.forEach { event ->
            require(isLegalStreamTransition(previous, event)) { "illegal script: $event may not follow $previous" }
            previous = event
        }
    }

    /** The most recent request, for asserting request construction. */
    var lastRequest: LlmRequest? = null
        private set

    /** Number of times [stream] was collected. */
    var streamCount: Int = 0
        private set

    /** Number of collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    /** Number of events emitted (deltas plus terminal events). */
    var emittedEventCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> {
        // Capability preflight mirrors a real adapter: an unsupported request
        // fails before streaming rather than being silently downgraded.
        val unsupported =
            request.reasoning
                ?.takeIf { it != ReasoningLevel.NONE && it !in capabilities.reasoningLevels }
        if (unsupported != null) {
            return flow {
                emittedEventCount++
                emit(
                    LlmStreamEvent.Failed(
                        error = VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST),
                        partialText = "",
                    ),
                )
            }
        }
        return flow {
            streamCount++
            lastRequest = request
            var previous: LlmStreamEvent? = null
            script.forEach { event ->
                if (eventDelayMillis > 0L) delay(eventDelayMillis)
                check(isLegalStreamTransition(previous, event)) { "illegal scripted transition: $event after $previous" }
                previous = event
                emittedEventCount++
                emit(event)
            }
        }.onCompletion { cause ->
            if (cause is CancellationException) cancellationCount++
        }
    }

    override suspend fun close() {
        closed = true
    }
}
