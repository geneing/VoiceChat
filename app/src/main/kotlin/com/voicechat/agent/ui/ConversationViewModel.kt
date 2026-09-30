package com.voicechat.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.domain.context.ModelContextBuilder
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.orchestration.TurnObserver
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.orchestration.TurnOutcome
import com.voicechat.agent.orchestration.TurnRequest
import com.voicechat.agent.orchestration.TurnResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * It is the single seam between Compose and the M02 contracts. Conversation-level
 * concerns — the history list, opening/deleting a conversation, the composer, and
 * notices — stay here; **the generation pipeline itself is owned by the M21
 * [TurnOrchestrator]**.
 *
 * - **One path for text and voice.** [onSend] and the voice seam
 *   ([setProvisionalTranscript] / [commitProvisionalTranscript]) both funnel into
 *   one [submitTurn], which appends a finalized [UserTurn] and hands it to the
 *   orchestrator, which persists it before starting the provider request. Manual
 *   text is never a special case.
 * - **Correction is not rewriting.** The composer draft is editable and is
 *   persisted exactly as submitted; recognition output is only ever provisional
 *   until the user commits a (possibly corrected) final text.
 * - **Truthful streaming.** The orchestrator renders assistant deltas live and
 *   persists only delivered text at a terminal state; a cancelled or failed reply
 *   is stored as such, with only the delivered prefix.
 *
 * **Tracing.** Stage events go through the orchestrator's M04
 * [com.voicechat.agent.diagnostics.TurnTraceRecorder]; only counts and stable
 * codes are recorded. Transcript, prompt, and credential content never are.
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
    contextBuilder: ModelContextBuilder = ModelContextBuilder(),
    private val textToSpeech: TextToSpeech? = null,
    scope: CoroutineScope? = null,
) : ViewModel(),
    ConversationActions {
    private val orchestrator =
        TurnOrchestrator(
            repository = repository,
            languageModel = languageModel,
            textToSpeech = textToSpeech,
            diagnostics = diagnostics,
            clock = clock,
            wallClock = wallClock,
            assistantTurnId = { idFactory.newTurnId() },
            contextBuilder = contextBuilder,
        )

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

    /** Identity token for the active generation, so stale work cannot update the UI. */
    private var activeGeneration: Any? = null

    /** Set while the holder is being cleared, so teardown does not reconcile a turn. */
    private var closed: Boolean = false

    private fun coroutineScope(): CoroutineScope = externalScope ?: viewModelScope

    // region ConversationActions

    override fun onNewConversation() {
        discardActiveGeneration()
        AppLog.d { "ui: new conversation" }
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
        AppLog.d { "ui: open conversation" }
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
                    AppLog.w(failure) { "ui: open conversation failed" }
                    _uiState.update { it.openFailed(failure.toNotice()) }
                    return@launch
                }
            if (loaded == null) {
                AppLog.w { "ui: open conversation not found" }
                _uiState.update { it.openFailed(ConversationNotice.Failure(ErrorCode.PERSISTENCE_FAILED, retryable = true)) }
                return@launch
            }
            // A reopen is where mid-turn state from a dead process is reconciled so
            // the conversation never claims work a restart could not finish.
            val reconciled = loaded.reconcileAfterProcessDeath()
            if (reconciled != loaded) {
                AppLog.i { "ui: reconciled conversation after process death turns=${reconciled.turns.size}" }
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
        AppLog.d { "ui: cancel requested" }
        // Optimistic feedback; the orchestration persists the interrupted turn and
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
        AppLog.d { "ui: retry" }
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
                // Drop the failed/cancelled reply (and any later turns) before re-running;
                // orchestration re-appends and re-persists the same user turn.
                val truncated =
                    conversation.copy(
                        updatedAtEpochMillis = wallClock(),
                        turns = conversation.turns.take(lastUserIndex),
                    )
                runOrchestratedTurn(truncated, lastUser, lastUser.transcript.text, marker)
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
                AppLog.e(failure) { "ui: delete conversation failed" }
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
     * This is the M06 seam the voice loop calls; the text is displayed as
     * provisional and is never persisted or sent until
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
        AppLog.d { "ui: commit voice transcript chars=${committedText.length}" }
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
        AppLog.d { "ui: submit turn source=$source chars=${committed.length}" }
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
        val now = wallClock()
        val base =
            currentConversation?.copy()
                ?: Conversation(
                    id = idFactory.newConversationId(),
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
        val title = base.title ?: titleFrom(committed)
        val userTurn =
            UserTurn(
                id = idFactory.newTurnId(),
                transcript = Transcript.final(committed),
                source = source,
            )
        val titled = base.copy(title = title, updatedAtEpochMillis = now)
        generationJob =
            coroutineScope().launch(dispatcher) {
                runOrchestratedTurn(titled, userTurn, committed, marker)
            }
    }

    /** Hands one turn to the orchestrator and maps each callback to UI state. */
    private suspend fun runOrchestratedTurn(
        conversation: Conversation,
        userTurn: UserTurn,
        originalText: String,
        marker: Any,
    ) {
        val observer =
            object : TurnObserver {
                override fun onUserTurnCommitted(conversation: Conversation) {
                    if (activeGeneration !== marker) return
                    currentConversation = conversation
                    _uiState.update { state ->
                        state.copy(
                            dialog =
                                state.dialog?.copy(
                                    conversationId = conversation.id,
                                    title = conversation.title,
                                    turns = conversation.turns,
                                    phase = TurnPhase.GENERATING,
                                    liveAssistantText = "",
                                ),
                        )
                    }
                }

                override fun onLiveAssistantText(text: String) {
                    if (activeGeneration !== marker) return
                    _uiState.update { state -> state.copy(dialog = state.dialog?.copy(liveAssistantText = text)) }
                }

                override fun onTurnFinished(result: TurnResult) {
                    applyTurnResult(marker, result, originalText)
                }
            }
        orchestrator.run(
            TurnRequest(
                conversation = conversation,
                userTurn = userTurn,
                selection = selection,
                reasoning = reasoning,
            ),
            observer,
        )
    }

    /**
     * Applies the orchestrator's terminal result to the dialog.
     *
     * Only a user-turn persistence failure restores the draft; otherwise the
     * persisted turns are adopted and the phase/notice are derived from the
     * typed [TurnOutcome], so a cancelled or failed reply is never shown as a
     * completed one.
     */
    private fun applyTurnResult(
        marker: Any,
        result: TurnResult,
        originalText: String,
    ) {
        if (closed) return
        if (activeGeneration !== marker) return
        if (!result.userTurnPersisted) {
            handleStorageFailure(marker, originalText)
            return
        }
        val record = result.record
        val error = record?.failure
        val outcome = record?.outcome
        val notice =
            when {
                result.persistenceFailure != null -> {
                    ConversationNotice.Failure(ErrorCode.PERSISTENCE_FAILED, retryable = true)
                }

                outcome is TurnOutcome.ProviderError && error != null -> {
                    error.toNotice()
                }

                outcome is TurnOutcome.TtsFailure && error != null -> {
                    error.toNotice()
                }

                outcome is TurnOutcome.Cancelled -> {
                    ConversationNotice.RequestCancelled
                }

                outcome is TurnOutcome.Interrupted -> {
                    ConversationNotice.RequestCancelled
                }

                else -> {
                    null
                }
            }
        val phase = record?.phase ?: TurnPhase.IDLE
        AppLog.d {
            "ui: turn terminal phase=$phase outcome=${outcome?.let { it::class.simpleName } ?: "none"} " +
                "generated=${record?.generatedText?.length ?: 0} delivered=${record?.deliveredText?.length ?: 0}"
        }
        currentConversation = result.conversation
        activeGeneration = null
        _uiState.update { state ->
            val dialog = state.dialog ?: return@update state
            state.copy(
                dialog =
                    dialog.copy(
                        turns = result.conversation.turns,
                        liveAssistantText = null,
                        phase = phase,
                        notice = notice,
                    ),
            )
        }
    }

    private fun handleStorageFailure(
        marker: Any,
        originalText: String,
    ) {
        if (closed) return
        if (activeGeneration !== marker) return
        AppLog.w { "ui: persistence failed; restored draft chars=${originalText.length}" }
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
    textToSpeech: TextToSpeech? = null,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            ConversationViewModel(
                repository = repository,
                languageModel = languageModel,
                selection = selection,
                reasoning = reasoning,
                diagnostics = diagnostics,
                textToSpeech = textToSpeech,
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

                is com.voicechat.agent.domain.AssistantTurn -> {
                    when (last.generated.state) {
                        com.voicechat.agent.domain.GenerationState.CANCELLED -> TurnPhase.CANCELLED
                        com.voicechat.agent.domain.GenerationState.FAILED -> TurnPhase.FAILED
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

private fun VoiceAgentError.toNotice(): ConversationNotice = ConversationNotice.Failure(code, retryable)

private fun Throwable.toNotice(): ConversationNotice {
    val error = (this as? VoiceAgentException)?.error
    return if (error != null) {
        ConversationNotice.Failure(error.code, error.retryable)
    } else {
        ConversationNotice.Failure(ErrorCode.UNKNOWN, retryable = false)
    }
}
