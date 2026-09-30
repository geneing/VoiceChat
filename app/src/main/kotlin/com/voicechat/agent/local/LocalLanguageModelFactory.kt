package com.voicechat.agent.local

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.domain.ModelId

/**
 * Builds the on-device [LanguageModel] for a selected local model id (M20).
 *
 * It is the local counterpart of `ProviderLanguageModelFactory`; it never
 * returns a remote adapter, so an on-device selection can never be served by a
 * network provider (and vice versa). Returning `null` means "this app has no
 * usable adapter for that local model", which the caller keeps as an explicit
 * not-configured state rather than fabricating a reply.
 */
fun interface LocalLanguageModelFactory {
    /** Creates the adapter for [modelId], or `null` when it is not usable here. */
    fun create(modelId: ModelId): LanguageModel?
}

/**
 * The catalog-driven [LocalLanguageModelFactory].
 *
 * AICore is matched by its well-known id and served by [AicoreLanguageModel];
 * an app-managed id is served by [LiteRtLmLanguageModel] only when it is on the
 * allow-list. Any other id resolves to `null`.
 *
 * The native-backed defaults ([MlKitPromptGenerator], [EngineLiteRtLmSessionFactory])
 * are constructed only when this factory is created; tests and previews always
 * inject fakes so no device or native runtime is required.
 */
class CatalogLocalLanguageModelFactory(
    private val installer: LocalModelInstaller,
    private val generator: LocalTextGenerator = MlKitPromptGenerator(),
    private val sessions: LiteRtLmSessionFactory = EngineLiteRtLmSessionFactory(),
) : LocalLanguageModelFactory {
    override fun create(modelId: ModelId): LanguageModel? =
        when {
            modelId == LocalModels.GEMINI_NANO_ID -> {
                AicoreLanguageModel(generator = generator)
            }

            else -> {
                LocalModelCatalog.find(modelId)?.let { model ->
                    LiteRtLmLanguageModel(model = model, installer = installer, sessions = sessions)
                }
            }
        }
}
