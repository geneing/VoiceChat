package com.voicechat.agent.ui

import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.providers.ProviderDisclosure
import com.voicechat.agent.voice.VoiceSessionState

/**
 * Immutable UI state for the conversation surface (M06).
 *
 * These are plain values the Compose tree renders; they contain no provider,
 * speech-SDK, or Room type, and the state holder derives them from the M02
 * contracts. Keeping the rendering state separate from the domain lets the
 * dialog show provisional and streaming content that is not (yet) persisted.
 */
data class ConversationUiState(
    val list: ConversationListState = ConversationListState(),
    val dialog: ConversationDialogState? = null,
    val pendingDeletion: PendingDeletion? = null,
) {
    /** The dialog is shown whenever a [dialog] is open; otherwise the list is. */
    val screen: ConversationScreen get() = if (dialog != null) ConversationScreen.DIALOG else ConversationScreen.LIST
}

/** Which top-level surface is visible. */
enum class ConversationScreen {
    LIST,
    DIALOG,
}

/**
 * A user-visible, non-sensitive notice.
 *
 * It carries only a stable [ErrorCode] or a cancellation marker; provider
 * detail, transcript text, prompts, and credentials are deliberately absent so
 * nothing sensitive can reach the UI from an error path.
 */
sealed interface ConversationNotice {
    /** A request or storage failure. */
    data class Failure(
        val code: ErrorCode,
        val retryable: Boolean,
    ) : ConversationNotice

    /** The user cancelled the in-flight request. */
    data object RequestCancelled : ConversationNotice
}

/** A delete that is waiting for the user to confirm it. */
data class PendingDeletion(
    val id: ConversationId,
    val label: String?,
)

/** State for the conversation history list. */
data class ConversationListState(
    val summaries: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = true,
    val notice: ConversationNotice? = null,
) {
    /** True once history loaded and there is nothing to show. */
    val isEmpty: Boolean get() = !isLoading && summaries.isEmpty()
}

/**
 * State for one open dialog.
 *
 * [turns] are the committed, persisted turns. [provisionalUserText] is live STT
 * that is not yet committed and is shown as provisional. [liveAssistantText] is
 * the in-flight assistant delta text: it is rendered incrementally and is only
 * persisted (truthfully) when generation reaches a terminal state.
 */
data class ConversationDialogState(
    val conversationId: ConversationId? = null,
    val title: String? = null,
    val turns: List<Turn> = emptyList(),
    val provisionalUserText: String? = null,
    val liveAssistantText: String? = null,
    val phase: TurnPhase = TurnPhase.IDLE,
    val composerText: String = "",
    val isLoading: Boolean = false,
    val notice: ConversationNotice? = null,
    /**
     * Where this conversation's next request will go, from the persisted M22
     * selection (M23). Shown before/at send so the destination, the remote
     * text/context transfer, and the provider's retention note are disclosed
     * (R-0097, R-0139). [ProviderDisclosure.NONE] until a provider/model is
     * selected, which the dialog renders as the honest not-configured hint.
     */
    val provider: ProviderDisclosure = ProviderDisclosure.NONE,
    /**
     * Session state of the M24 voice loop. [VoiceSessionState.IDLE] when no voice
     * session is running, so the dialog renders the manual text path unchanged.
     */
    val voiceState: VoiceSessionState = VoiceSessionState.IDLE,
    /** True when a voice session can be started (the app attached a voice factory). */
    val voiceAvailable: Boolean = false,
) {
    /** True while an LLM request is streaming. */
    val isGenerating: Boolean get() = phase == TurnPhase.GENERATING

    /** True while the voice loop is listening, working, or speaking. */
    val isVoiceActive: Boolean get() = voiceState != VoiceSessionState.IDLE && voiceState != VoiceSessionState.STOPPED

    /** True when the last request was cancelled or failed and can be re-run. */
    val canRetry: Boolean get() = phase == TurnPhase.CANCELLED || phase == TurnPhase.FAILED

    /** True when the composer has non-blank text and nothing is streaming. */
    val canSend: Boolean get() = composerText.isNotBlank() && !isGenerating
}

/**
 * User intents from the conversation UI to the state holder.
 *
 * The Compose tree depends on this interface, never on a ViewModel or provider,
 * so screens stay decoupled from a specific state-holder implementation.
 */
interface ConversationActions {
    /** Opens an empty, not-yet-persisted conversation. */
    fun onNewConversation()

    /** Opens a persisted conversation by ID. */
    fun onOpenConversation(id: ConversationId)

    /** Returns to the conversation list. */
    fun onBackToList()

    /** Updates the editable composer draft. */
    fun onComposerChanged(text: String)

    /** Submits the composer draft as a user turn. */
    fun onSend()

    /** Cancels the in-flight request. */
    fun onCancel()

    /** Re-runs generation for the most recent user turn. */
    fun onRetry()

    /** Asks for confirmation before deleting a conversation. */
    fun onRequestDelete(id: ConversationId)

    /** Confirms the pending deletion. */
    fun onConfirmDelete()

    /** Dismisses the pending deletion. */
    fun onDismissDelete()

    /** Dismisses the current notice. */
    fun onDismissNotice()
}
