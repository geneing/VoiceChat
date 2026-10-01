package com.voicechat.agent.providers

/**
 * The provider/model a conversation turn will run with (M23).
 *
 * It is a small, provider-neutral display value derived from the persisted M22
 * selection and the M13 registry: just enough for a screen to name the active
 * provider and model.
 *
 * **No destination, retention, or transfer warning.** The conversation surface
 * deliberately does not carry a data-transfer notice card; asking the user to
 * configure a provider and credential is the consent step. The registry still
 * records each provider's destination/retention facts (`ServerDestination`,
 * `dataRetentionNote`, `toolExecutionOnServer`) for documentation and for any
 * future explicit consent surface; they are simply not rendered as banners.
 *
 * [hasSelection] is true only when both a provider and a model are selected; the
 * dialog uses it to keep the honest "not configured" hint otherwise.
 */
data class ProviderDisclosure(
    val providerDisplayName: String? = null,
    val modelId: String? = null,
) {
    /** True when a provider and model are both selected. */
    val hasSelection: Boolean get() = providerDisplayName != null && modelId != null

    companion object {
        /** Nothing selected: the honest starting state. */
        val NONE: ProviderDisclosure = ProviderDisclosure()

        /**
         * Builds the identity value for [settings] against [registry].
         *
         * A selection with an unknown provider, or no selection at all, yields
         * [NONE] rather than a partially-guessed value.
         */
        fun from(
            settings: com.voicechat.agent.settings.VoiceSettings,
            registry: ProviderCapabilityRegistry,
        ): ProviderDisclosure {
            val selection = settings.llmSelection ?: return NONE
            val provider = registry.capabilities(selection.providerId) ?: return NONE
            return ProviderDisclosure(
                providerDisplayName = provider.displayName,
                modelId = selection.modelId.value,
            )
        }
    }
}
