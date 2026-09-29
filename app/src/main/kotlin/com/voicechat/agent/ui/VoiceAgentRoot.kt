package com.voicechat.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.domain.ProviderModelSelection

/**
 * Stateful root of the Compose tree.
 *
 * It binds the lifecycle-aware [ConversationViewModel] to the stateless
 * [ConversationApp]: dependencies enter as the M02 contracts, the ViewModel holds
 * the state, and the UI collects it with the owner lifecycle. Nothing here
 * depends on Room, a speech SDK, or a specific LLM provider, so M21 can replace
 * the state holder without touching the screens.
 */
@Composable
fun VoiceAgentRoot(
    repository: ConversationRepository,
    languageModel: LanguageModel,
    selection: ProviderModelSelection = ConversationDefaults.selection,
    modifier: Modifier = Modifier,
) {
    val viewModel: ConversationViewModel =
        viewModel(
            factory =
                conversationViewModelFactory(
                    repository = repository,
                    languageModel = languageModel,
                    selection = selection,
                ),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ConversationApp(state = state, actions = viewModel, modifier = modifier)
}
