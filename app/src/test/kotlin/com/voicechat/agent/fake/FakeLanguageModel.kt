package com.voicechat.agent.fake

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Deterministic [LanguageModel] that replays a scripted stream.
 *
 * With [eventDelayMillis] greater than zero the flow suspends before each
 * event, so tests using `runTest` can cancel partway through and assert that
 * cancellation stops the stream. No network or credentials are involved.
 */
class FakeLanguageModel(
    override val providerId: ProviderId = ProviderId("fake-provider"),
    private val script: List<LlmStreamEvent> = listOf(LlmStreamEvent.Completed()),
    private val eventDelayMillis: Long = 0L,
) : LanguageModel {
    /** The most recent request, for asserting request construction. */
    var lastRequest: LlmRequest? = null
        private set

    /** Number of times [stream] was collected. */
    var streamCount: Int = 0
        private set

    /** Number of collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            streamCount++
            lastRequest = request
            script.forEach { event ->
                if (eventDelayMillis > 0L) delay(eventDelayMillis)
                emit(event)
            }
        }.onCompletion { cause ->
            if (cause is CancellationException) cancellationCount++
        }

    override suspend fun close() {
        closed = true
    }
}
