package com.voicechat.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.diagnostics.TurnTraceFactory
import com.voicechat.agent.diagnostics.TurnTraceRecorder
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.domain.context.ContextMessage
import com.voicechat.agent.domain.context.ContextRole
import com.voicechat.agent.domain.context.ModelContextBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Generates the stable IDs the conversation layers assign to conversations and turns. */
interface ConversationIdFactory {
    /** @return a new conversation identifier. */
    fun newConversationId(): ConversationId

    /** @return a new turn identifier. */
    fun newTurnId(): TurnId
}

/** Default [ConversationIdFactory] backed by random UUIDs. */
object UuidConversationIdFactory : ConversationIdFactory {
    override fun newConversationId(): ConversationId = ConversationId("conversation-${UUID.randomUUID()}")

    override fun newTurnId(): TurnId = TurnId("turn-${UUID.randomUUID()}")
}

/**
 * Lifecycle-aware state holder for the conversation UI (M06).
 *
 * It is the single seam between Compose and the M02 [ConversationRepository] /
 * [LanguageModel] contracts. It owns the manual-text path and the shared
 * turn/conversation path that voice will reuse in M21:
 *
 * - **One path for text and voice.** [onSend] and the voice seam
 *   ([setProvisionalTranscript] / [commitProvisionalTranscript]) both funnel into
 *   one [submitTurn], which appends a finalized [UserTurn] and persists it
 *   through the repository before generation starts. Manual text is never a
 *   special case.
 * - **Correction is not rewriting.** The composer draft is editable and is
 *   persisted exactly as submitted; recognition output is only ever provisional
 *   until the user commits a (possibly corrected) final text.
 * - **Truthful streaming.** Assistant deltas are rendered live from
 *   [ConversationDialogState.liveAssistantText] but are persisted only at a
 *   terminal state. On cancel/failure the persisted turn keeps generation
 *   `CANCELLED`/`FAILED` and delivery `INTERRUPTED`, with only the delivered
 *   prefix, so a cancelled response is never stored as complete.
 *
 * The class is deliberately not the full M21 turn state machine: it covers one
 * manual/text request at a time and leaves barge-in, STT, and TTS integration to
 * later milestones.
 *
 * **Tracing.** Stage events go through the M04 [TurnTraceFactory] /
 * [TurnTraceRecorder] seam, never a parallel logger. Only counts and stable
 * codes are recorded; transcript, prompt, and credential content never are.
 */
class ConversationViewModel(
    private val repository: ConversationRepository,
    private val languageModel: LanguageModel,
    private val selection: ProviderModelSelection,
    private val reasoning: ReasoningLevel? = null,
    diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: ConversationIdFactory = UuidConversationIdFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val contextBuilder: ModelContextBuilder = ModelContextBuilder(),
    scope: CoroutineScope? = null,
) : ViewModel(),
    ConversationActions {
    private val traceFactory = TurnTraceFactory(clock, diagnostics)

    /** External scope for tests; production uses the ViewModel's own scope. */
    private val externalScope = scope

    private val _uiState = MutableStateFlow(ConversationUiState())

    /** Observable, immutable UI state. */
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private val listJob: Job =
        coroutineScope().launch(dispatcher) {
            repository
                .observeConversations()
                .catch { failure -> _uiState.update { it.copy(list = it.list.copy(notice = failure.toNotice())) } }
                .collect { summaries ->
                    _uiState.update { state ->
                        state.copy(list = state.list.copy(summaries = summaries, isLoading = false))
                    }
                }
        }

    /** The last loaded or saved conversation; the source of truth for the open dialog. */
    private var currentConversation: Conversation? = null

    /** The active generation job, if any. */
    private var generationJob: Job? = null

    /** Identity token for the active generation, so stale jobs cannot update the UI. */
    private var activeGeneration: Any? = null

    /** Set while the holder is being cleared, so teardown does not reconcile a turn. */
    private var closed: Boolean = false

    private fun coroutineScope(): CoroutineScope = externalScope ?: viewModelScope

    // region ConversationActions

    override fun onNewConversation() {
        discardActiveGeneration()
        currentConversation = null
        _uiState.update {
            it.copy(
                dialog = ConversationDialogState(),
                pendingDeletion = null,
            )
        }
    }

    override fun onOpenConversation(id: ConversationId) {
        discardActiveGeneration()
        currentConversation = null
        _uiState.update { state ->
            state.copy(
                dialog =
                    ConversationDialogState(
                        conversationId = id,
                        isLoading = true,
                    ),
                pendingDeletion = null,
            )
        }
        coroutineScope().launch(dispatcher) {
            val loaded =
                try {
                    repository.load(id)
                } catch (failure: Throwable) {
                    _uiState.update { it.openFailed(failure.toNotice()) }
                    return@launch
                }
            if (loaded == null) {
                _uiState.update { it.openFailed(ConversationNotice.Failure(ErrorCode.PERSISTENCE_FAILED, retryable = true)) }
                return@launch
            }
            // A reopen is where mid-turn state from a dead process is reconciled so
            // the conversation never claims work a restart could not finish.
            val reconciled = loaded.reconcileAfterProcessDeath()
            if (reconciled != loaded) {
                saveQuietly(reconciled)
            }
            currentConversation = reconciled
            _uiState.update { state -> state.copy(dialog = reconciled.toDialogState()) }
        }
    }

    override fun onBackToList() {
        discardActiveGeneration()
        _uiState.update { it.copy(dialog = null, pendingDeletion = null) }
    }

    override fun onComposerChanged(text: String) {
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(composerText = text, notice = null)) }
    }

    override fun onSend() {
        val dialog = _uiState.value.dialog ?: return
        submitTurn(dialog.composerText, UserTurnSource.TEXT)
    }

    override fun onCancel() {
        val job = generationJob ?: return
        if (!job.isActive) return
        // Optimistic feedback; the generation job persists the interrupted turn and
        // then finalizes the state with the persisted turns.
        _uiState.update { state ->
            state.copy(
                dialog = state.dialog?.copy(phase = TurnPhase.CANCELLED, notice = ConversationNotice.RequestCancelled),
            )
        }
        generationJob = null
        job.cancel()
    }

    override fun onRetry() {
        if (generationJob?.isActive == true) return
        val conversation = currentConversation ?: return
        val lastUserIndex = conversation.turns.indexOfLast { it is UserTurn }
        if (lastUserIndex < 0) return
        val lastUser = conversation.turns[lastUserIndex] as UserTurn
        val marker = beginGeneration()
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        phase = TurnPhase.GENERATING,
                        liveAssistantText = "",
                        notice = null,
                    ),
            )
        }
        generationJob =
            coroutineScope().launch(dispatcher) {
                // Drop the failed/cancelled reply (and any later turns) before re-running.
                val retried =
                    conversation.copy(
                        updatedAtEpochMillis = wallClock(),
                        turns = conversation.turns.take(lastUserIndex + 1),
                    )
                try {
                    repository.save(retried)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    handleStorageFailure(marker, lastUser.transcript.text)
                    return@launch
                }
                currentConversation = retried
                if (activeGeneration !== marker) return@launch
                _uiState.update { state ->
                    state.copy(
                        dialog =
                            state.dialog?.copy(
                                conversationId = retried.id,
                                title = retried.title,
                                turns = retried.turns,
                                phase = TurnPhase.GENERATING,
                                liveAssistantText = "",
                            ),
                    )
                }
                val trace = traceFactory.start(lastUser.id, selection, reasoningLevel = reasoning?.name)
                generate(retried, lastUser.id, trace, marker)
            }
    }

    override fun onRequestDelete(id: ConversationId) {
        val state = _uiState.value
        val label =
            state.list.summaries
                .firstOrNull { it.id == id }
                ?.title
                ?: state.dialog?.takeIf { it.conversationId == id }?.title
        _uiState.update { it.copy(pendingDeletion = PendingDeletion(id, label)) }
    }

    override fun onConfirmDelete() {
        val pending = _uiState.value.pendingDeletion ?: return
        _uiState.update { it.copy(pendingDeletion = null) }
        if (currentConversation?.id == pending.id) {
            discardActiveGeneration()
            currentConversation = null
        }
        coroutineScope().launch(dispatcher) {
            try {
                repository.delete(pending.id)
            } catch (failure: Throwable) {
                _uiState.update { state -> state.copy(list = state.list.copy(notice = failure.toNotice())) }
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    dialog = state.dialog?.takeIf { it.conversationId != pending.id },
                )
            }
        }
    }

    override fun onDismissDelete() {
        _uiState.update { it.copy(pendingDeletion = null) }
    }

    override fun onDismissNotice() {
        _uiState.update { state ->
            state.copy(
                list = state.list.copy(notice = null),
                dialog = state.dialog?.copy(notice = null),
            )
        }
    }

    // endregion

    // region Voice seam (shared turn path)

    /**
     * Shows live STT text as provisional (not committed).
     *
     * This is the M06 seam the M21 voice loop will call; the text is displayed
     * as provisional and is never persisted or sent until
     * [commitProvisionalTranscript] finalizes it.
     */
    fun setProvisionalTranscript(text: String) {
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(provisionalUserText = text)) }
    }

    /**
     * Commits a finalized (optionally user-corrected) transcript as a voice turn.
     *
     * The committed text is exactly [committedText]: the recognizer's raw
     * hypothesis is shown only as provisional and is never silently rewritten.
     * This uses the same [submitTurn] path as manual text.
     */
    fun commitProvisionalTranscript(committedText: String) {
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(provisionalUserText = null)) }
        submitTurn(committedText, UserTurnSource.VOICE)
    }

    // endregion

    /** Runs the shared turn path for text and voice. */
    private fun submitTurn(
        text: String,
        source: UserTurnSource,
    ) {
        val committed = text.trim()
        if (committed.isEmpty()) return
        if (generationJob?.isActive == true) return
        val marker = beginGeneration()
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        composerText = "",
                        provisionalUserText = null,
                        liveAssistantText = "",
                        phase = TurnPhase.GENERATING,
                        notice = null,
                    ),
            )
        }
        generationJob =
            coroutineScope().launch(dispatcher) {
                runTurn(idFactory.newTurnId(), committed, source, marker)
            }
    }

    private suspend fun runTurn(
        turnId: TurnId,
        text: String,
        source: UserTurnSource,
        marker: Any,
    ) {
        val trace = traceFactory.start(turnId, selection, reasoningLevel = reasoning?.name)
        val now = wallClock()
        val base =
            currentConversation?.copy()
                ?: Conversation(
                    id = idFactory.newConversationId(),
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
        val title = base.title ?: titleFrom(text)
        val userTurn =
            UserTurn(
                id = turnId,
                transcript = Transcript.final(text),
                source = source,
            )
        val withUser =
            base.copy(
                title = title,
                updatedAtEpochMillis = now,
                turns = base.turns + userTurn,
            )

        val saveSpan = trace.start(DiagnosticStage.PERSISTENCE)
        try {
            repository.save(withUser)
            saveSpan.succeed()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            saveSpan.fail()
            handleStorageFailure(marker, text)
            return
        }
        currentConversation = withUser
        if (activeGeneration !== marker) return
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        conversationId = withUser.id,
                        title = title,
                        turns = withUser.turns,
                        phase = TurnPhase.GENERATING,
                        liveAssistantText = "",
                    ),
            )
        }
        generate(withUser, turnId, trace, marker)
    }

    private suspend fun generate(
        conversation: Conversation,
        userTurnId: TurnId,
        trace: TurnTraceRecorder,
        marker: Any,
    ) {
        val request =
            LlmRequest(
                model = selection,
                messages = contextBuilder.build(conversation).messages.map { it.toLlmMessage() },
                reasoning = reasoning,
            )
        val requestSpan = trace.start(DiagnosticStage.LLM_REQUEST)
        trace.requestSelected(selection.providerId.value, selection.modelId.value, reasoning?.name)
        trace.markStreamStarted()

        val rendered = StringBuilder()
        var terminal = false
        try {
            languageModel.stream(request).collect { event ->
                when (event) {
                    is LlmStreamEvent.Delta -> {
                        rendered.append(event.text)
                        trace.llmDelta(clock.nanoTime(), event.text.length)
                        publishLiveAssistant(marker, rendered.toString())
                    }

                    is LlmStreamEvent.Completed -> {
                        requestSpan.succeed()
                        terminal = true
                        finishTurn(
                            conversation = conversation,
                            generatedText = rendered.toString(),
                            deliveredText = rendered.toString(),
                            generationState = GenerationState.COMPLETED,
                            deliveryState = DeliveryState.COMPLETED,
                            error = null,
                            trace = trace,
                            marker = marker,
                        )
                    }

                    is LlmStreamEvent.Cancelled -> {
                        requestSpan.cancel()
                        terminal = true
                        finishTurn(
                            conversation = conversation,
                            generatedText = event.partialText,
                            deliveredText = rendered.toString(),
                            generationState = GenerationState.CANCELLED,
                            deliveryState = DeliveryState.INTERRUPTED,
                            error = null,
                            trace = trace,
                            marker = marker,
                        )
                    }

                    is LlmStreamEvent.Failed -> {
                        requestSpan.fail()
                        terminal = true
                        finishTurn(
                            conversation = conversation,
                            generatedText = event.partialText,
                            deliveredText = rendered.toString(),
                            generationState = GenerationState.FAILED,
                            deliveryState = DeliveryState.FAILED,
                            error = event.error,
                            trace = trace,
                            marker = marker,
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            // The user cancelled or the holder is going away. Persist what was
            // actually shown as an interrupted turn; never as a completed reply.
            if (!terminal && !closed) {
                withContext(NonCancellable) {
                    requestSpan.cancel()
                    finishTurn(
                        conversation = conversation,
                        generatedText = rendered.toString(),
                        deliveredText = rendered.toString(),
                        generationState = GenerationState.CANCELLED,
                        deliveryState = DeliveryState.INTERRUPTED,
                        error = null,
                        trace = trace,
                        marker = marker,
                    )
                }
            }
            throw cancellation
        }
    }

    /**
     * Persists a terminal assistant turn and publishes the final UI state.
     *
     * Delivery never exceeds generation: [deliveredText] is kept only when it is a
     * prefix of [generatedText]. A turn with no generated and no delivered text is
     * not persisted, so a cancelled-before-any-output request leaves no phantom
     * reply.
     */
    private suspend fun finishTurn(
        conversation: Conversation,
        generatedText: String,
        deliveredText: String,
        generationState: GenerationState,
        deliveryState: DeliveryState,
        error: VoiceAgentError?,
        trace: TurnTraceRecorder,
        marker: Any,
    ) {
        val outcome = persistAssistant(conversation, generatedText, deliveredText, generationState, deliveryState, marker)
        when (generationState) {
            GenerationState.COMPLETED -> {
                trace.turnCompleted()
            }

            GenerationState.FAILED -> {
                trace.error(DiagnosticStage.LLM_REQUEST, (error?.code ?: ErrorCode.LLM_REQUEST_FAILED).name)
                trace.turnEnded(DiagnosticOutcome.FAILED)
            }

            GenerationState.CANCELLED -> {
                trace.turnEnded(DiagnosticOutcome.CANCELLED)
            }

            GenerationState.IN_PROGRESS -> {
                Unit
            }
        }
        if (closed) return
        if (activeGeneration !== marker) return
        val persistedTurns = (outcome as? PersistOutcome.Saved)?.conversation?.turns
        val persistNotice = (outcome as? PersistOutcome.Failed)?.notice
        activeGeneration = null
        _uiState.update { state ->
            val dialog = state.dialog ?: return@update state
            state.copy(
                dialog =
                    dialog.copy(
                        turns = persistedTurns ?: dialog.turns,
                        liveAssistantText = null,
                        phase =
                            when (generationState) {
                                GenerationState.COMPLETED -> TurnPhase.COMPLETED
                                GenerationState.CANCELLED -> TurnPhase.CANCELLED
                                GenerationState.FAILED -> TurnPhase.FAILED
                                GenerationState.IN_PROGRESS -> TurnPhase.GENERATING
                            },
                        notice =
                            when {
                                persistNotice != null -> persistNotice
                                error != null -> ConversationNotice.Failure(error.code, error.retryable)
                                generationState == GenerationState.CANCELLED -> ConversationNotice.RequestCancelled
                                else -> null
                            },
                    ),
            )
        }
    }

    private suspend fun persistAssistant(
        conversation: Conversation,
        generatedText: String,
        deliveredText: String,
        generationState: GenerationState,
        deliveryState: DeliveryState,
        marker: Any,
    ): PersistOutcome {
        if (generatedText.isEmpty() && deliveredText.isEmpty()) return PersistOutcome.Skipped
        val delivered = if (generatedText.startsWith(deliveredText)) deliveredText else generatedText
        val assistant =
            AssistantTurn(
                id = idFactory.newTurnId(),
                generated = GeneratedText(text = generatedText, state = generationState),
                delivery = AssistantDelivery(deliveredText = delivered, state = deliveryState),
            )
        val updated =
            conversation.copy(
                updatedAtEpochMillis = wallClock(),
                turns = conversation.turns + assistant,
            )
        try {
            repository.save(updated)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return PersistOutcome.Failed(failure.toNotice())
        }
        // Only adopt the saved conversation when this generation still owns the UI;
        // a navigation away leaves the repository updated without clobbering the
        // newly opened conversation in memory.
        if (activeGeneration === marker) {
            currentConversation = updated
        }
        return PersistOutcome.Saved(updated)
    }

    private fun publishLiveAssistant(
        marker: Any,
        text: String,
    ) {
        if (activeGeneration !== marker) return
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(liveAssistantText = text)) }
    }

    private fun handleStorageFailure(
        marker: Any,
        originalText: String,
    ) {
        if (closed) return
        if (activeGeneration !== marker) return
        activeGeneration = null
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        // Do not lose the user's text: restore it so it can be corrected and resent.
                        composerText = originalText,
                        liveAssistantText = null,
                        phase = TurnPhase.IDLE,
                        notice = ConversationNotice.Failure(ErrorCode.PERSISTENCE_FAILED, retryable = true),
                    ),
            )
        }
    }

    private fun beginGeneration(): Any {
        val marker = Any()
        activeGeneration = marker
        return marker
    }

    private fun discardActiveGeneration() {
        activeGeneration = null
        generationJob?.cancel()
        generationJob = null
    }

    private suspend fun saveQuietly(conversation: Conversation) {
        try {
            repository.save(conversation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // Recovery couldn't be written back; the in-memory view is still truthful.
        }
    }

    /** Cancels in-flight work; called by [onCleared] and by tests. Idempotent. */
    fun shutdown() {
        closed = true
        activeGeneration = null
        listJob.cancel()
        generationJob?.cancel()
        generationJob = null
    }

    override fun onCleared() {
        shutdown()
    }

    private companion object {
        const val TITLE_MAX_LENGTH = 40

        fun titleFrom(text: String): String {
            val normalized = text.trim().replace(Regex("\\s+"), " ")
            return if (normalized.length <= TITLE_MAX_LENGTH) {
                normalized
            } else {
                normalized.take(TITLE_MAX_LENGTH).trimEnd() + "…"
            }
        }
    }
}

/** Builds the production [ConversationViewModel] for the app's Compose tree. */
fun conversationViewModelFactory(
    repository: ConversationRepository,
    languageModel: LanguageModel,
    selection: ProviderModelSelection,
    reasoning: ReasoningLevel? = null,
    diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            ConversationViewModel(
                repository = repository,
                languageModel = languageModel,
                selection = selection,
                reasoning = reasoning,
                diagnostics = diagnostics,
            )
        }
    }

private fun Conversation.toDialogState(): ConversationDialogState =
    ConversationDialogState(
        conversationId = id,
        title = title,
        turns = turns,
        phase =
            when (val last = turns.lastOrNull()) {
                null -> {
                    TurnPhase.IDLE
                }

                is UserTurn -> {
                    TurnPhase.CANCELLED
                }

                is AssistantTurn -> {
                    when (last.generated.state) {
                        GenerationState.CANCELLED -> TurnPhase.CANCELLED
                        GenerationState.FAILED -> TurnPhase.FAILED
                        else -> TurnPhase.IDLE
                    }
                }
            },
    )

private fun ConversationUiState.openFailed(notice: ConversationNotice): ConversationUiState =
    copy(
        dialog = null,
        list = list.copy(notice = notice),
    )

private fun ContextMessage.toLlmMessage(): LlmMessage =
    LlmMessage(
        role =
            when (role) {
                ContextRole.USER -> LlmRole.USER
                ContextRole.ASSISTANT -> LlmRole.ASSISTANT
            },
        content = text,
    )

/** Result of writing a terminal assistant turn. */
private sealed interface PersistOutcome {
    /** The turn was written and this is the resulting conversation. */
    data class Saved(
        val conversation: Conversation,
    ) : PersistOutcome

    /** The write failed; [notice] is safe to show. */
    data class Failed(
        val notice: ConversationNotice,
    ) : PersistOutcome

    /** There was nothing worth persisting. */
    data object Skipped : PersistOutcome
}

private fun Throwable.toNotice(): ConversationNotice {
    val error = (this as? VoiceAgentException)?.error
    return if (error != null) {
        ConversationNotice.Failure(error.code, error.retryable)
    } else {
        ConversationNotice.Failure(ErrorCode.UNKNOWN, retryable = false)
    }
}
