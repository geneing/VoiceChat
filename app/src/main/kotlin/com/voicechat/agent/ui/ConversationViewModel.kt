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
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderDisclosure
import com.voicechat.agent.providers.ProviderLanguageModelFactory
import com.voicechat.agent.settings.VoiceSettings
import com.voicechat.agent.voice.BargeInTiming
import com.voicechat.agent.voice.VoiceInterruptionRecovery
import com.voicechat.agent.voice.VoiceSessionController
import com.voicechat.agent.voice.VoiceSessionFactory
import com.voicechat.agent.voice.VoiceSessionListener
import com.voicechat.agent.voice.VoiceSessionState
import com.voicechat.agent.voice.VoiceTurnProvider
import com.voicechat.agent.voice.VoiceTurnProviderSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
    /**
     * When supplied with [providerRegistry] and [providerFactory], the turn path
     * resolves the active provider/model and adapter from the persisted M22
     * selection at send time (M23, R-0103) instead of using the fixed
     * [languageModel]/[selection] above. `null` keeps the fixed behavior the M06
     * tests rely on.
     */
    private val settingsFlow: Flow<VoiceSettings>? = null,
    private val providerRegistry: ProviderCapabilityRegistry? = null,
    private val providerFactory: ProviderLanguageModelFactory? = null,
    /**
     * The on-device model factory (M20). When supplied, a persisted local-model
     * selection is resolved to an on-device adapter instead of a remote one; the
     * two never cross over.
     */
    private val localModelFactory: com.voicechat.agent.local.LocalLanguageModelFactory? = null,
) : ViewModel(),
    ConversationActions,
    VoiceSessionListener,
    VoiceTurnProviderSource {
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

    // region M23 provider resolution

    /** True when the persisted selection drives the turn path. */
    private val providerResolutionEnabled: Boolean =
        settingsFlow != null && providerRegistry != null && providerFactory != null

    /** The latest validated settings; only used when [providerResolutionEnabled]. */
    private var latestSettings: VoiceSettings = VoiceSettings.EMPTY

    /** What the open dialog discloses about the selected provider; kept current. */
    private var currentDisclosure: ProviderDisclosure = ProviderDisclosure.NONE

    /**
     * Re-derives the disclosure for the open dialog whenever the persisted
     * settings change, so a selection made in Settings is visible in the dialog
     * before the next send without restarting the conversation.
     */
    private val settingsJob: Job? =
        if (providerResolutionEnabled) {
            coroutineScope().launch(dispatcher) {
                settingsFlow!!
                    .catch { failure -> AppLog.w(failure) { "ui: settings observation failed" } }
                    .collect { stored ->
                        latestSettings = stored
                        applyDisclosure(ProviderDisclosure.from(stored, providerRegistry!!))
                    }
            }
        } else {
            null
        }

    // endregion

    /** The last loaded or saved conversation; the source of truth for the open dialog. */
    private var currentConversation: Conversation? = null

    /** The active generation job, if any. */
    private var generationJob: Job? = null

    /** Identity token for the active generation, so stale work cannot update the UI. */
    private var activeGeneration: Any? = null

    /** Set while the holder is being cleared, so teardown does not reconcile a turn. */
    private var closed: Boolean = false

    // region M24 voice session

    /** Builds a fresh voice session when the app attached a platform factory (null = voice off). */
    private var voiceFactory: VoiceSessionFactory? = null

    /** The active voice session controller, if any. */
    private var voiceController: VoiceSessionController? = null

    /** The job running the active voice session. */
    private var voiceJob: Job? = null

    /**
     * Attaches the app-boundary [VoiceSessionFactory].
     *
     * Until this is called the dialog shows no voice control and behaves exactly
     * as the M06/M23 text path — so the voice loop is additive and never changes
     * existing behavior. The [ConversationViewModel] is both the session's UI
     * [VoiceSessionListener] and its [VoiceTurnProviderSource].
     */
    fun attachVoiceSession(factory: VoiceSessionFactory) {
        voiceFactory = factory
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(voiceAvailable = true)) }
    }

    /** True when a voice session can be started. */
    val voiceAvailable: Boolean get() = voiceFactory != null

    /** Starts a voice session over the open conversation (or a fresh one). */
    fun onStartVoice() {
        val factory = voiceFactory ?: return
        if (voiceJob?.isActive == true || generationJob?.isActive == true) return
        refreshDisclosure()
        val now = wallClock()
        val base =
            currentConversation?.copy()
                ?: Conversation(
                    id = idFactory.newConversationId(),
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
        AppLog.d { "ui: voice session start" }
        _uiState.update { state ->
            state.copy(
                dialog =
                    (state.dialog ?: ConversationDialogState(provider = currentDisclosure)).copy(
                        conversationId = base.id,
                        provisionalUserText = null,
                        liveAssistantText = "",
                        phase = TurnPhase.LISTENING,
                        notice = null,
                        provider = currentDisclosure,
                        voiceAvailable = true,
                        voiceState = VoiceSessionState.LISTENING,
                    ),
            )
        }
        val controller = factory.create(this, this)
        voiceController = controller
        voiceJob =
            coroutineScope().launch(dispatcher) {
                try {
                    controller.run(base)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    AppLog.w(failure) { "ui: voice session failed" }
                    _uiState.update { state ->
                        state.copy(dialog = state.dialog?.copy(notice = failure.toNotice()))
                    }
                } finally {
                    voiceController = null
                    _uiState.update { state ->
                        state.copy(
                            dialog =
                                state.dialog?.copy(
                                    voiceState = VoiceSessionState.IDLE,
                                    provisionalUserText = null,
                                ),
                        )
                    }
                }
            }
    }

    /** Stops the active voice session and returns the dialog to the idle state. */
    fun onStopVoice() {
        AppLog.d { "ui: voice session stop" }
        discardVoiceSession()
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        voiceState = VoiceSessionState.IDLE,
                        provisionalUserText = null,
                        phase = if (state.dialog?.isGenerating == true) state.dialog.phase else TurnPhase.IDLE,
                    ),
            )
        }
    }

    private fun discardVoiceSession() {
        voiceController?.stop()
        voiceController = null
        voiceJob?.cancel()
        voiceJob = null
    }

    /** Resolves the adapter/identity for the next voice turn from the persisted selection. */
    override fun providerFor(conversation: Conversation): VoiceTurnProvider {
        val active = activeProvider(conversation.id)
        return VoiceTurnProvider(
            selection = active.selection,
            reasoning = active.reasoning,
            languageModel = active.languageModel,
        )
    }

    // endregion

    // region VoiceSessionListener (voice loop -> dialog)

    override fun onSessionState(state: VoiceSessionState) {
        _uiState.update { current ->
            current.copy(
                dialog =
                    current.dialog?.copy(
                        voiceState = state,
                        phase = state.toTurnPhase(current.dialog?.phase ?: TurnPhase.IDLE),
                    ),
            )
        }
    }

    override fun onListeningStarted(turnId: TurnId) {
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        provisionalUserText = null,
                        liveAssistantText = null,
                    ),
            )
        }
    }

    override fun onProvisionalTranscript(
        turnId: TurnId,
        text: String,
    ) {
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(provisionalUserText = text)) }
    }

    override fun onUtteranceCommitted(
        turnId: TurnId,
        transcript: Transcript,
    ) {
        _uiState.update { state ->
            state.copy(dialog = state.dialog?.copy(provisionalUserText = null, phase = TurnPhase.GENERATING))
        }
    }

    override fun onAssistantText(
        turnId: TurnId,
        text: String,
    ) {
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(liveAssistantText = text)) }
    }

    override fun onConversationChanged(conversation: Conversation) {
        currentConversation = conversation
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        conversationId = conversation.id,
                        title = conversation.title,
                        turns = conversation.turns,
                    ),
            )
        }
    }

    override fun onTurnFinished(result: TurnResult) {
        currentConversation = result.conversation
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

                else -> {
                    null
                }
            }
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        turns = result.conversation.turns,
                        liveAssistantText = null,
                        phase = record?.phase ?: TurnPhase.IDLE,
                        notice = notice,
                    ),
            )
        }
    }

    override fun onBargeIn(timing: BargeInTiming) {
        AppLog.d { "ui: voice barge-in stop=${timing.onsetToStopNanos}ns" }
        // The interrupted reply settles asynchronously and is persisted with only
        // its delivered prefix; the dialog is not told a false completed turn.
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(notice = ConversationNotice.RequestCancelled)) }
    }

    override fun onInterruptionRecovered(recovery: VoiceInterruptionRecovery) {
        if (recovery == VoiceInterruptionRecovery.NO_USABLE_SPEECH) {
            AppLog.d { "ui: barge-in recovery: noise/no-speech" }
            // A false/noise interruption commits no turn; the dialog stays usable.
            _uiState.update { state -> state.copy(dialog = state.dialog?.copy(notice = null)) }
        }
    }

    override fun onError(error: VoiceAgentError) {
        AppLog.w { "ui: voice session error code=${error.code}" }
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(notice = error.toNotice())) }
    }

    override fun onTextToSpeechUnavailable(error: VoiceAgentError) {
        AppLog.w { "ui: voice session is text-only code=${error.code}" }
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(notice = error.toNotice())) }
    }

    private fun VoiceSessionState.toTurnPhase(current: TurnPhase): TurnPhase =
        when (this) {
            VoiceSessionState.LISTENING -> TurnPhase.LISTENING
            VoiceSessionState.WORKING, VoiceSessionState.SPEAKING -> TurnPhase.GENERATING
            VoiceSessionState.FAILED -> TurnPhase.FAILED
            VoiceSessionState.IDLE, VoiceSessionState.STOPPED -> if (current == TurnPhase.SPEAKING) current else TurnPhase.IDLE
        }

    // endregion

    private fun coroutineScope(): CoroutineScope = externalScope ?: viewModelScope

    // region Provider resolution (M23)

    /** Stores [disclosure] and shows it on the open dialog, if any. */
    private fun applyDisclosure(disclosure: ProviderDisclosure) {
        currentDisclosure = disclosure
        _uiState.update { state -> state.copy(dialog = state.dialog?.copy(provider = disclosure)) }
    }

    /** Recomputes the disclosure synchronously from the latest observed settings. */
    private fun refreshDisclosure() {
        if (!providerResolutionEnabled) return
        applyDisclosure(ProviderDisclosure.from(latestSettings, providerRegistry!!))
    }

    /**
     * Resolves the identity and adapter for a turn in [conversationId].
     *
     * With no settings source this returns the fixed constructor
     * `languageModel`/`selection`, preserving the pre-M23 behavior that the M06
     * tests exercise.
     */
    private fun activeProvider(conversationId: ConversationId): ActiveProviderTurn =
        if (providerResolutionEnabled) {
            ProviderTurnResolver.resolve(
                settings = latestSettings,
                conversationId = conversationId,
                registry = providerRegistry!!,
                factory = providerFactory!!,
                localFactory = localModelFactory,
            )
        } else {
            ActiveProviderTurn(
                selection = selection,
                reasoning = reasoning,
                languageModel = languageModel,
                configured = true,
            )
        }

    // endregion

    // region ConversationActions

    override fun onNewConversation() {
        discardActiveGeneration()
        discardVoiceSession()
        AppLog.d { "ui: new conversation" }
        currentConversation = null
        refreshDisclosure()
        _uiState.update {
            it.copy(
                dialog = ConversationDialogState(provider = currentDisclosure, voiceAvailable = voiceFactory != null),
                pendingDeletion = null,
            )
        }
    }

    override fun onOpenConversation(id: ConversationId) {
        discardActiveGeneration()
        discardVoiceSession()
        AppLog.d { "ui: open conversation" }
        currentConversation = null
        refreshDisclosure()
        _uiState.update { state ->
            state.copy(
                dialog =
                    ConversationDialogState(
                        conversationId = id,
                        isLoading = true,
                        provider = currentDisclosure,
                        voiceAvailable = voiceFactory != null,
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
            _uiState.update { state ->
                state.copy(
                    dialog =
                        reconciled.toDialogState(currentDisclosure).copy(
                            voiceAvailable =
                                voiceFactory != null,
                        ),
                )
            }
        }
    }

    override fun onBackToList() {
        discardActiveGeneration()
        discardVoiceSession()
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
        refreshDisclosure()
        val marker = beginGeneration()
        _uiState.update { state ->
            state.copy(
                dialog =
                    state.dialog?.copy(
                        phase = TurnPhase.GENERATING,
                        liveAssistantText = "",
                        notice = null,
                        provider = currentDisclosure,
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
            discardVoiceSession()
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
        refreshDisclosure()
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
                        provider = currentDisclosure,
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
        val active = activeProvider(conversation.id)
        if (providerResolutionEnabled && !active.configured) {
            AppLog.w { "ui: no provider/model configured; the turn will report LLM_NOT_CONFIGURED" }
        }
        orchestrator.run(
            TurnRequest(
                conversation = conversation,
                userTurn = userTurn,
                selection = active.selection,
                reasoning = active.reasoning,
            ),
            observer,
            languageModel = active.languageModel,
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
        settingsJob?.cancel()
        generationJob?.cancel()
        generationJob = null
        discardVoiceSession()
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
    settingsFlow: Flow<VoiceSettings>? = null,
    providerRegistry: ProviderCapabilityRegistry? = null,
    providerFactory: ProviderLanguageModelFactory? = null,
    /** The M20 on-device model factory; `null` keeps the remote-only behavior. */
    localModelFactory: com.voicechat.agent.local.LocalLanguageModelFactory? = null,
    /**
     * The M24 app-boundary voice factory. When supplied, the ViewModel attaches it
     * and the dialog gains a voice control; when null the app stays text-only and
     * every M06/M21/M23 behavior is unchanged.
     */
    voiceSessionFactory: VoiceSessionFactory? = null,
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
                settingsFlow = settingsFlow,
                providerRegistry = providerRegistry,
                providerFactory = providerFactory,
                localModelFactory = localModelFactory,
            ).also { viewModel ->
                voiceSessionFactory?.let(viewModel::attachVoiceSession)
            }
        }
    }

private fun Conversation.toDialogState(provider: ProviderDisclosure): ConversationDialogState =
    ConversationDialogState(
        conversationId = id,
        title = title,
        turns = turns,
        provider = provider,
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
