package com.voicechat.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderLanguageModelFactory
import com.voicechat.agent.settings.VoiceSettings
import kotlinx.coroutines.flow.Flow

/**
 * Stateful root of the Compose tree.
 *
 * It binds the lifecycle-aware [ConversationViewModel] to the stateless
 * [ConversationApp]: dependencies enter as the M02 contracts, the ViewModel holds
 * the state, and the UI collects it with the owner lifecycle. Nothing here
 * depends on Room, a speech SDK, or a specific LLM provider.
 *
 * When a [settingsFactory] is supplied the root also hosts the M22
 * [SettingsScreen], reachable from the conversation list. The factory is built at
 * the app boundary from the settings store, the provider capability registry, and
 * the credential store, so no platform type leaks into the screens.
 */
@Composable
fun VoiceAgentRoot(
    repository: ConversationRepository,
    languageModel: LanguageModel,
    selection: ProviderModelSelection = ConversationDefaults.selection,
    modifier: Modifier = Modifier,
    settingsFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    settingsFlow: Flow<VoiceSettings>? = null,
    providerRegistry: ProviderCapabilityRegistry? = null,
    providerFactory: ProviderLanguageModelFactory? = null,
) {
    val viewModel: ConversationViewModel =
        viewModel(
            factory =
                conversationViewModelFactory(
                    repository = repository,
                    languageModel = languageModel,
                    selection = selection,
                    settingsFlow = settingsFlow,
                    providerRegistry = providerRegistry,
                    providerFactory = providerFactory,
                ),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (settingsFactory == null) {
        ConversationApp(state = state, actions = viewModel, modifier = modifier)
        return
    }

    val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory)
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            state = settingsState,
            actions = settingsViewModel,
            onBack = { showSettings = false },
            modifier = modifier,
        )
    } else {
        ConversationApp(
            state = state,
            actions = viewModel,
            modifier = modifier,
            onOpenSettings = { showSettings = true },
        )
    }
}
