package com.voicechat.agent.providers

/**
 * What the conversation surface shows about where a request will go, before it
 * is sent (M23).
 *
 * It is a small, provider-neutral display value derived from the persisted M22
 * selection and the M13 registry, so the dialog can disclose the destination and
 * the remote text/context transfer **before** any text leaves the device (R-0097)
 * and can name the selected provider's retention/training behavior where the
 * registry records one (R-0139). It carries no credential, prompt, or transcript
 * content.
 *
 * [hasSelection] is true only when both a provider and a model are selected; the
 * dialog uses it to keep the honest "not configured" hint otherwise.
 */
data class ProviderDisclosure(
    val providerDisplayName: String? = null,
    val modelId: String? = null,
    val destination: String? = null,
    val remoteTransfer: Boolean = false,
    val toolExecutionOnServer: Boolean = false,
    val retentionNotice: String? = null,
) {
    /** True when a provider and model are both selected. */
    val hasSelection: Boolean get() = providerDisplayName != null && modelId != null

    companion object {
        /** Nothing selected: the honest starting state. */
        val NONE: ProviderDisclosure = ProviderDisclosure()

        /**
         * Builds the disclosure for [settings] against [registry].
         *
         * A selection with an unknown provider, or no selection at all, yields
         * [NONE] rather than a partially-guessed disclosure. The destination is
         * the validated `ServerDestination.disclosure()` from the provider
         * endpoint policy (the same value the M22 settings surface shows); it is
         * `null` when the provider has no validated destination yet (an
         * unconfigured Hermes server).
         */
        fun from(
            settings: com.voicechat.agent.settings.VoiceSettings,
            registry: ProviderCapabilityRegistry,
        ): ProviderDisclosure {
            val selection = settings.llmSelection ?: return NONE
            val provider = registry.capabilities(selection.providerId) ?: return NONE
            val destination =
                (
                    ProviderEndpointPolicy.destinationFor(provider, settings.llmServerUrl)
                        as? EndpointValidation.Valid
                )?.destination?.disclosure()
            return ProviderDisclosure(
                providerDisplayName = provider.displayName,
                modelId = selection.modelId.value,
                destination = destination,
                remoteTransfer = true,
                toolExecutionOnServer = provider.toolExecutionOnServer,
                retentionNotice = provider.dataRetentionNote,
            )
        }
    }
}
