package com.voicechat.agent.local

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRequestValidation
import com.voicechat.agent.contracts.LlmRequestValidator
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The AICore / Gemini Nano [LanguageModel] (M20).
 *
 * It implements the **same** M12 contract as the M14–M19 remote adapters, so
 * orchestration and the dialog do not know whether a turn ran on-device. It is
 * backed by a [LocalTextGenerator] (the real one is [MlKitPromptGenerator]) so
 * request→stream mapping and error typing are JVM-tested with a fake and no
 * AICore. AICore models are system-managed: this adapter only generates; it never
 * downloads, copies, inspects, or deletes model files.
 */
class AicoreLanguageModel(
    private val generator: LocalTextGenerator,
    override val providerId: ProviderId = LocalProviders.AICORE,
    private val modelId: ModelId = LocalModels.GEMINI_NANO_ID,
) : LanguageModel {
    override val capabilities: LlmCapabilities get() = generator.capabilities

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        localLanguageModelStream(
            request = request,
            capabilities = capabilities,
            modelId = modelId,
            produce = { prompt -> generator.generate(prompt) },
        )

    override suspend fun close() = Unit
}

/**
 * The LiteRT-LM `.litertlm` [LanguageModel] (M20).
 *
 * It loads only an **installed, integrity-verified** allow-listed artifact in
 * app-private storage; a missing, partial, or corrupt file is an explicit
 * not-configured/unavailable failure, never a successful init. The native
 * runtime is behind [LiteRtLmSessionFactory], so this adapter is JVM-tested with
 * a fake session and no native library or model.
 */
class LiteRtLmLanguageModel(
    private val model: ValidatedLocalModel,
    private val installer: LocalModelInstaller,
    private val sessions: LiteRtLmSessionFactory,
    override val providerId: ProviderId = LocalProviders.LITERT_LM,
) : LanguageModel {
    override val capabilities: LlmCapabilities =
        LlmCapabilities(streaming = true, usageReporting = false, reasoningLevels = emptySet())

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        localLanguageModelStream(
            request = request,
            capabilities = capabilities,
            modelId = model.descriptor.id,
            produce = { prompt ->
                flow {
                    val session = openVerifiedSession()
                    session.use { open ->
                        open.generate(prompt).collect { emit(it) }
                    }
                }
            },
        )

    override suspend fun close() = Unit

    /** Opens a session only for an installed, checksum-verified artifact. */
    private suspend fun openVerifiedSession(): LiteRtLmSession =
        when (val state = installer.state(model)) {
            is LocalInstallState.Installed -> {
                sessions.open(state.path)
            }

            LocalInstallState.NotInstalled -> {
                throw VoiceAgentException(
                    VoiceAgentError(ErrorCode.LLM_NOT_CONFIGURED, "the local model is not installed"),
                )
            }

            is LocalInstallState.Downloading -> {
                throw VoiceAgentException(
                    VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the local model is still downloading"),
                )
            }

            is LocalInstallState.IntegrityFailed -> {
                throw VoiceAgentException(
                    VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the local model failed integrity verification"),
                )
            }

            is LocalInstallState.SizeMismatch -> {
                throw VoiceAgentException(
                    VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the local model file size does not match"),
                )
            }

            is LocalInstallState.Failed -> {
                throw VoiceAgentException(state.error)
            }
        }
}

/**
 * Shared request→stream mapping for the local adapters.
 *
 * Exactly like the remote adapters: validate the request against the declared
 * capabilities before generating, stream deltas, and end with exactly one
 * terminal event. A generator failure becomes a typed
 * [LlmStreamEvent.Failed] carrying the text received so far; cancellation
 * propagates; nothing is reported complete unless generation actually finished.
 * The prompt is never logged.
 */
internal fun localLanguageModelStream(
    request: LlmRequest,
    capabilities: LlmCapabilities,
    modelId: ModelId,
    produce: suspend (String) -> Flow<String>,
): Flow<LlmStreamEvent> =
    flow {
        val validation = LlmRequestValidator.validate(request, capabilities)
        if (validation is LlmRequestValidation.Unsupported) {
            emit(LlmStreamEvent.Failed(error = validation.error, partialText = ""))
            return@flow
        }

        val prompt = LocalPrompt.render(request)
        val received = StringBuilder()
        try {
            produce(prompt).collect { text ->
                if (text.isNotEmpty()) {
                    received.append(text)
                    emit(LlmStreamEvent.Delta(text))
                }
            }
            emit(LlmStreamEvent.Completed(usage = null, model = modelId, reasoning = null))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: VoiceAgentException) {
            emit(LlmStreamEvent.Failed(error = failure.error, partialText = received.toString()))
        } catch (failure: Throwable) {
            emit(
                LlmStreamEvent.Failed(
                    error =
                        VoiceAgentError(
                            code = ErrorCode.LLM_UNAVAILABLE,
                            detail = "on-device generation failed (${failure.javaClass.simpleName})",
                        ),
                    partialText = received.toString(),
                ),
            )
        }
    }
