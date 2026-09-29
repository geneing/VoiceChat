package com.voicechat.agent.fake

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelAvailabilityProvider
import com.voicechat.agent.contracts.ModelTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Deterministic [ModelAvailabilityProvider] backed by a fixed map.
 *
 * A task with no entry emits an empty list, which is the explicit "nothing is
 * compatible" result rather than an implied default.
 */
class FakeModelAvailabilityProvider(
    private val availability: Map<ModelTask, List<ModelAvailability>> = emptyMap(),
) : ModelAvailabilityProvider {
    override fun observe(task: ModelTask): Flow<List<ModelAvailability>> = flowOf(availability[task].orEmpty())
}
