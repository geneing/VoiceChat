package com.voicechat.agent.orchestration

import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.tts.TtsPlaybackAccounting

/**
 * The authoritative, pure-Kotlin state machine for one conversation turn.
 *
 * It is deliberately synchronous, `android.*`-free, and contract-independent:
 * the orchestrator translates provider/TTS callbacks into [TurnEvent]s, calls
 * [reduce], and reads a [TurnRecord] snapshot. Because the transitions are a
 * plain function of the current state and the event, a deterministic JVM test
 * can drive a whole turn — including revisions, out-of-order late events, and
 * cancellation races — without a dispatcher, a device, or a network.
 *
 * **Turn IDs drop stale work.** Every event names its turn; an event whose ID is
 * not the machine's current turn (or that arrives after the turn already
 * finished) is rejected and counted in [staleEventDrops], never applied. A
 * cancelled or superseded turn therefore cannot mutate a newer turn, and a late
 * `ProviderCompleted` after a cancel cannot flip it back to success.
 *
 * **Truthful accounting.** Generated text, queued TTS text, and delivered text
 * are tracked with the M11 [TtsPlaybackAccounting] ledger and the
 * [GeneratedText]/[AssistantDelivery] domain split. [assistantTurn] returns a
 * turn only when there is something truthful to persist, and its delivered text
 * is always a prefix of the generated text.
 *
 * @param speechEnabled whether this turn delivers through TTS. When false
 *   (text-only), delivery mirrors generation and the turn can settle as soon as
 *   the stream ends.
 */
class TurnStateMachine(
    private val speechEnabled: Boolean = false,
) {
    private var accounting = TtsPlaybackAccounting()

    /** Text the provider reported for a terminal event, authoritative over deltas. */
    private var generatedOverride: String? = null

    private var listeningTurnId: TurnId? = null
    private var listeningSource: UserTurnSource? = null
    private var listeningRejection: TranscriptRejection? = null

    private var activeTurnId: TurnId? = null
    private var activeSource: UserTurnSource? = null
    private var committedTranscript: Transcript? = null

    private var currentPhase: TurnPhase = TurnPhase.IDLE
    private var generation: GenerationState? = null
    private var explicitDelivery: DeliveryState? = null
    private var currentOutcome: TurnOutcome? = null
    private var currentFailure: VoiceAgentError? = null

    private var cancelRequested: Boolean = false
    private var interruptRequested: Boolean = false
    private var interruptedOnsetAtNanos: Long? = null
    private var ttsFailure: VoiceAgentError? = null
    private var queuedUtterances: Int = 0
    private var startedUtterances: Int = 0
    private var deliveredUtterances: Int = 0

    /** Current phase; terminal once the turn finished. */
    val phase: TurnPhase get() = currentPhase

    /** The committed generation turn ID, or `null` before generation starts. */
    val turnId: TurnId? get() = activeTurnId

    /** The current generation state, or `null` before generation starts. */
    val generationState: GenerationState? get() = generation

    /** The typed terminal outcome, or `null` while the turn is still active. */
    val outcome: TurnOutcome? get() = currentOutcome

    /** The typed failure when the turn failed, or `null`. */
    val failure: VoiceAgentError? get() = currentFailure

    /** Assistant text generated for the turn. */
    val generatedText: String get() = generatedOverride ?: accounting.generatedText

    /** Assistant text accepted for synthesis (a prefix of [generatedText]). */
    val queuedText: String get() = accounting.queuedText

    /** Assistant text confirmed audible (a prefix of [generatedText]). */
    val deliveredText: String get() = deliveryFor(generatedText).deliveredText

    /** Number of events rejected because they did not belong to the active turn. */
    var staleEventDrops: Int = 0
        private set

    /** The active listening turn, if transcription is still in progress. */
    val listening: ListeningRecord?
        get() {
            val id = listeningTurnId ?: return null
            return ListeningRecord(
                turnId = id,
                source = listeningSource!!,
                provisional = listeningProvisional,
                rejection = listeningRejection,
            )
        }

    private var listeningProvisional: Transcript? = null

    /** Begins a voice turn that is still being transcribed. */
    fun beginListening(
        turnId: TurnId,
        source: UserTurnSource = UserTurnSource.VOICE,
    ): ListeningRecord {
        listeningTurnId = turnId
        listeningSource = source
        listeningProvisional = null
        listeningRejection = null
        currentPhase = TurnPhase.IDLE.transitionTo(TurnPhase.LISTENING)
        return listening!!
    }

    /**
     * Begins generation for a finalized (optionally user-corrected) transcript.
     *
     * Manual text uses this directly; a voice turn calls it after listening.
     */
    fun beginGeneration(
        turnId: TurnId,
        source: UserTurnSource,
        transcript: Transcript,
    ) {
        activeTurnId = turnId
        activeSource = source
        committedTranscript = transcript
        listeningTurnId = null
        listeningSource = null
        listeningProvisional = null
        listeningRejection = null
        accounting = TtsPlaybackAccounting()
        generatedOverride = null
        queuedUtterances = 0
        startedUtterances = 0
        deliveredUtterances = 0
        currentPhase = TurnPhase.IDLE.transitionTo(TurnPhase.GENERATING)
        generation = GenerationState.IN_PROGRESS
        explicitDelivery = null
        currentOutcome = null
        currentFailure = null
        cancelRequested = false
        interruptRequested = false
        interruptedOnsetAtNanos = null
        ttsFailure = null
    }

    /**
     * Applies one event.
     *
     * @return `true` when the event belonged to the active turn and was applied;
     *   `false` when it was dropped as stale (the caller must not act on it).
     */
    fun reduce(event: TurnEvent): Boolean {
        // Listening: only interim revisions and a rejection are valid here.
        when (event) {
            is TurnEvent.Provisional -> {
                if (event.turnId != listeningTurnId) return drop()
                val current = listeningProvisional
                val stale = current != null && event.transcript.revision.value <= current.revision.value
                if (stale) return drop()
                listeningProvisional = event.transcript
                return true
            }

            is TurnEvent.TranscriptRejected -> {
                if (event.turnId != listeningTurnId) return drop()
                listeningRejection = event.rejection
                currentPhase = TurnPhase.LISTENING.transitionTo(TurnPhase.IDLE)
                currentOutcome =
                    when (event.rejection) {
                        TranscriptRejection.NO_SPEECH -> TurnOutcome.NoSpeech(event.rejection)

                        TranscriptRejection.EMPTY,
                        TranscriptRejection.LOW_CONFIDENCE,
                        -> TurnOutcome.EmptyTranscript(event.rejection)
                    }
                return true
            }

            else -> {}
        }

        // Generation events must name the active turn and arrive before terminal.
        if (event.turnId != activeTurnId || currentPhase.isTerminal) return drop()

        when (event) {
            is TurnEvent.Provisional, is TurnEvent.TranscriptRejected -> {}

            is TurnEvent.ProviderDelta -> {
                accounting.onGenerated(event.text)
            }

            is TurnEvent.ProviderCompleted -> {
                generation = GenerationState.COMPLETED
            }

            is TurnEvent.ProviderFailed -> {
                generation = GenerationState.FAILED
                currentFailure = event.error
                reconcileTerminalText(event.partialText)
            }

            is TurnEvent.ProviderCancelled -> {
                generation = GenerationState.CANCELLED
                reconcileTerminalText(event.partialText)
            }

            is TurnEvent.TtsQueued -> {
                accounting.onEvent(event.utteranceId, TtsEvent.Queued(event.utteranceId, event.text))
                queuedUtterances++
                if (currentPhase == TurnPhase.GENERATING) {
                    currentPhase = TurnPhase.GENERATING.transitionTo(TurnPhase.SPEAKING)
                }
            }

            is TurnEvent.TtsStarted -> {
                accounting.onEvent(event.utteranceId, TtsEvent.Started(event.utteranceId, event.text))
                startedUtterances++
                if (currentPhase == TurnPhase.GENERATING) {
                    currentPhase = TurnPhase.GENERATING.transitionTo(TurnPhase.SPEAKING)
                }
            }

            is TurnEvent.TtsDelivered -> {
                accounting.onEvent(event.utteranceId, TtsEvent.Delivered(event.utteranceId, event.text))
                startedUtterances = maxOf(startedUtterances, deliveredUtterances + 1)
                deliveredUtterances++
            }

            is TurnEvent.TtsInterrupted -> {
                accounting.onEvent(event.utteranceId, TtsEvent.Interrupted(event.utteranceId, event.deliveredText))
                deliveredUtterances++
            }

            is TurnEvent.TtsFailed -> {
                accounting.onEvent(event.utteranceId, TtsEvent.Failed(event.error))
                ttsFailure = event.error
            }

            is TurnEvent.CancelRequested -> {
                cancelRequested = true
                generation = GenerationState.CANCELLED
            }

            is TurnEvent.InterruptRequested -> {
                interruptRequested = true
                interruptedOnsetAtNanos = event.onsetAtNanos
                generation = GenerationState.CANCELLED
            }
        }
        settle()
        return true
    }

    /**
     * The assistant turn to persist, or `null` when there is nothing truthful to
     * store (no generated and no delivered text).
     */
    fun assistantTurn(id: TurnId): AssistantTurn? {
        val generated = generatedText
        val delivery = deliveryFor(generated)
        if (generated.isEmpty() && delivery.deliveredText.isEmpty()) return null
        return AssistantTurn(
            id = id,
            generated = GeneratedText(text = generated, state = generation ?: GenerationState.IN_PROGRESS),
            delivery = delivery,
        )
    }

    /** A snapshot of the generation turn, or `null` before generation starts. */
    fun record(): TurnRecord? {
        val id = activeTurnId ?: return null
        val generated = generatedText
        val delivery = deliveryFor(generated)
        return TurnRecord(
            turnId = id,
            source = activeSource ?: UserTurnSource.TEXT,
            phase = currentPhase,
            transcript = committedTranscript ?: Transcript.final(""),
            generationState = generation ?: GenerationState.IN_PROGRESS,
            deliveryState = delivery.state,
            generatedText = generated,
            queuedText = queuedText,
            deliveredText = delivery.deliveredText,
            outcome = currentOutcome,
            failure = currentFailure,
            queuedUtteranceCount = queuedUtterances,
            startedUtteranceCount = startedUtterances,
            deliveredUtteranceCount = deliveredUtterances,
        )
    }

    /** The barge-in onset recorded for an interrupted turn, if any. */
    val interruptionOnsetAtNanos: Long? get() = interruptedOnsetAtNanos

    private fun reconcileTerminalText(partialText: String) {
        if (partialText.isNotEmpty() && partialText != accounting.generatedText) {
            // The adapter's terminal text is authoritative: it may supersede deltas.
            generatedOverride = partialText
        }
    }

    private fun settle() {
        if (!canSettle()) return
        val gen = generation ?: return
        when {
            gen == GenerationState.FAILED -> {
                finish(TurnPhase.FAILED, DeliveryState.FAILED, TurnOutcome.ProviderError(currentFailure!!))
            }

            ttsFailure != null -> {
                val error = ttsFailure!!
                generation = GenerationState.FAILED
                finish(TurnPhase.FAILED, DeliveryState.FAILED, TurnOutcome.TtsFailure(error))
            }

            cancelRequested -> {
                finish(TurnPhase.CANCELLED, DeliveryState.INTERRUPTED, TurnOutcome.Cancelled)
            }

            interruptRequested -> {
                finish(TurnPhase.INTERRUPTED, DeliveryState.INTERRUPTED, TurnOutcome.Interrupted)
            }

            gen == GenerationState.CANCELLED -> {
                finish(TurnPhase.CANCELLED, DeliveryState.INTERRUPTED, TurnOutcome.Cancelled)
            }

            gen == GenerationState.COMPLETED -> {
                when (deliveryFor(generatedText).state) {
                    DeliveryState.COMPLETED -> {
                        finish(TurnPhase.COMPLETED, DeliveryState.COMPLETED, TurnOutcome.Completed)
                    }

                    DeliveryState.INTERRUPTED -> {
                        finish(TurnPhase.INTERRUPTED, DeliveryState.INTERRUPTED, TurnOutcome.Interrupted)
                    }

                    DeliveryState.FAILED -> {
                        val error = ttsFailure ?: VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED)
                        generation = GenerationState.FAILED
                        finish(TurnPhase.FAILED, DeliveryState.FAILED, TurnOutcome.TtsFailure(error))
                    }

                    else -> {
                        finish(TurnPhase.COMPLETED, DeliveryState.COMPLETED, TurnOutcome.Completed)
                    }
                }
            }
        }
    }

    private fun canSettle(): Boolean {
        if (cancelRequested || interruptRequested) return true
        val gen = generation ?: return false
        if (gen == GenerationState.IN_PROGRESS) return false
        if (!speechEnabled) return true
        // Speech: wait until every queued utterance reached a terminal event.
        return accounting.isFullyAccounted
    }

    private fun finish(
        phase: TurnPhase,
        delivery: DeliveryState,
        outcome: TurnOutcome,
    ) {
        currentPhase = currentPhase.transitionTo(phase)
        explicitDelivery = delivery
        currentOutcome = outcome
        when (outcome) {
            is TurnOutcome.ProviderError -> {
                currentFailure = outcome.error
            }

            is TurnOutcome.TtsFailure -> {
                currentFailure = outcome.error
            }

            is TurnOutcome.PersistenceFailure -> {
                currentFailure = outcome.error
            }

            else -> {}
        }
    }

    private fun deliveryFor(generated: String): AssistantDelivery {
        val accounted =
            if (speechEnabled) {
                accounting.toDelivery(generated)
            } else {
                AssistantDelivery(deliveredText = generated, state = inferredDeliveryState())
            }
        val state = explicitDelivery ?: accounted.state
        return AssistantDelivery(deliveredText = accounted.deliveredText, state = state)
    }

    private fun inferredDeliveryState(): DeliveryState =
        when (generation) {
            GenerationState.COMPLETED -> DeliveryState.COMPLETED
            GenerationState.CANCELLED -> DeliveryState.INTERRUPTED
            GenerationState.FAILED -> DeliveryState.FAILED
            GenerationState.IN_PROGRESS, null -> DeliveryState.NOT_STARTED
        }

    private fun drop(): Boolean {
        staleEventDrops++
        return false
    }
}
