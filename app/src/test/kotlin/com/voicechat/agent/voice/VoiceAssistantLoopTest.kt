package com.voicechat.agent.voice

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeAudioInput
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.ManualVoiceTurnDetector
import com.voicechat.agent.fake.RecordingVoiceSessionListener
import com.voicechat.agent.fake.ScriptedSpeechToText
import com.voicechat.agent.orchestration.TurnOutcome
import com.voicechat.agent.vad.EndpointReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice loop with an assistant wired: a finalized transcript is answered, VAD
 * onset stops the reply immediately, and the loop keeps capturing throughout.
 *
 * These are the M24→M25 sequencing rules, so each case asserts the **order** of
 * the decisions, not only their outcome: the interrupt must happen at speech
 * onset with no transcript, no recognizer result, and no Smart Turn decision
 * standing in front of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAssistantLoopTest {
    private val conversation =
        Conversation(id = ConversationId("c1"), createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    @Test
    fun aFinalTranscriptIsAnsweredThroughTheAssistantWhichOwnsPersistence() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val listener = RecordingVoiceSessionListener()
            val assistant = RecordingVoiceAssistant(answer = "Lisbon is the capital.")
            val coordinator = coordinatorFor(repository, detector, listener, assistant, dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SEMANTIC_COMPLETE)
            advanceUntilIdle()

            val request = assistant.requests.single()
            assertEquals("hello", request.userTurn.transcript.text)
            // The assistant is handed the conversation *before* the user turn, so it
            // can persist both turns itself in the right order.
            assertEquals(0, request.conversation.turns.size)
            // And the loop must not persist a second copy of the user turn.
            assertNull(repository.load(conversation.id))
            assertEquals(
                "hello",
                listener.committed
                    .single()
                    .second.text,
            )
            // The assistant reports history in order: the user turn first, then the
            // conversation including the assistant turn.
            assertEquals(listOf(1, 2), listener.conversations.map { it.turns.size })
            assertEquals(listOf("Lisbon is the capital."), listener.liveAssistantText)
            assertEquals(listOf("Lisbon is the capital." to "Lisbon is the capital."), listener.spokenReplies)
            assertEquals(
                listOf(
                    VoiceSessionState.LISTENING,
                    VoiceSessionState.LISTENING,
                    VoiceSessionState.SPEAKING,
                    VoiceSessionState.LISTENING,
                ),
                listener.sessionStates,
            )

            detector.finish()
            session.join()
        }

    @Test
    fun speechOnsetInterruptsTheSpeakingAssistantBeforeAnyNewTranscriptExists() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val listener = RecordingVoiceSessionListener()
            val gate = CompletableDeferred<Unit>()
            val assistant =
                RecordingVoiceAssistant(
                    answer = "A long spoken answer that the user talks over.",
                    deliveryGate = gate,
                    deliveredPrefix = "A long spoken",
                )
            val coordinator = coordinatorFor(repository, detector, listener, assistant, dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SEMANTIC_COMPLETE)
            advanceUntilIdle()
            assertEquals(VoiceSessionState.SPEAKING, coordinator.state.value)
            assertEquals(1, assistant.requests.size)

            // The user talks over the reply: the loop is told about the onset only.
            // No transcript, no recognizer result, no endpoint has happened yet.
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()

            assertEquals("the onset must interrupt once", 1, assistant.interrupts.size)
            assertTrue("the interrupt must carry the onset time", assistant.interrupts.single() > 0L)
            assertEquals(VoiceSessionState.LISTENING, coordinator.state.value)
            assertEquals("the interrupting turn has not been transcribed yet", 1, listener.committed.size)
            assertEquals("onset must start the next recognition turn", 2, listener.listeningCount)
            assertEquals(
                "the onset must reach the assistant before anything else",
                listOf("reply-start", "interrupt"),
                assistant.events,
            )
            assertTrue("the loop must not wait for the interrupted reply", assistant.active)

            // The interrupted reply settles with only what was audible.
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf("A long spoken answer that the user talks over." to "A long spoken"),
                listener.spokenReplies,
            )
            assertTrue(assistant.active.not())
            assertEquals(VoiceSessionState.LISTENING, coordinator.state.value)

            detector.finish()
            session.join()
        }

    @Test
    fun aFailedReplyIsVisibleThroughTheListenerAndTheLoopKeepsListening() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val listener = RecordingVoiceSessionListener()
            val assistant =
                RecordingVoiceAssistant(
                    answer = "",
                    failure = VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable"),
                )
            val coordinator = coordinatorFor(repository, detector, listener, assistant, dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            advanceUntilIdle()

            assertEquals(ErrorCode.LLM_UNAVAILABLE, listener.errors.single().code)
            assertEquals(VoiceSessionState.LISTENING, coordinator.state.value)
            assertTrue("the loop must keep a turn open after a failure", listener.listeningCount >= 1)

            detector.finish()
            session.join()
        }

    private fun coordinatorFor(
        repository: InMemoryConversationRepository,
        detector: VoiceTurnDetector,
        listener: VoiceSessionListener,
        assistant: VoiceAssistant,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): VoiceSessionCoordinator =
        VoiceSessionCoordinator(
            audioInput = FakeAudioInput(),
            repository = repository,
            speechToText = ScriptedSpeechToText(finalText = "hello"),
            turnDetector = detector,
            assistant = assistant,
            listener = listener,
            clock = MonotonicClock { 10L },
            dispatcher = dispatcher,
            wallClock = { 2L },
        )
}

/**
 * A deterministic [VoiceAssistant] that records the call order.
 *
 * It persists nothing, so a test can prove the loop left persistence to the
 * assistant; it also mirrors the two behaviours the loop depends on: `reply`
 * suspends until the reply is delivered or interrupted, and `interrupt` releases
 * it with only the audible prefix.
 */
private class RecordingVoiceAssistant(
    private val answer: String,
    private val deliveryGate: CompletableDeferred<Unit>? = null,
    private val deliveredPrefix: String? = null,
    private val failure: VoiceAgentError? = null,
) : VoiceAssistant {
    val requests: MutableList<VoiceAssistantRequest> = mutableListOf()
    val interrupts: MutableList<Long> = mutableListOf()

    /** Names of the calls, in order, so a test can assert sequencing. */
    val events: MutableList<String> = mutableListOf()

    var active: Boolean = false
        private set

    override suspend fun reply(
        request: VoiceAssistantRequest,
        listener: VoiceSessionListener,
    ): VoiceAssistantReply {
        requests += request
        events += "reply-start"
        active = true
        listener.onUtteranceCommitted(request.userTurn.id, request.userTurn.transcript)
        val withUser = request.conversation.with(request.userTurn)
        listener.onConversationChanged(withUser)
        if (answer.isNotEmpty()) listener.onLiveAssistantText(answer)
        try {
            deliveryGate?.await()
        } finally {
            active = false
        }
        val delivered = deliveredPrefix ?: answer
        val withAssistant =
            withUser.copy(
                turns =
                    withUser.turns +
                        AssistantTurn(
                            id = TurnId("a1"),
                            generated = GeneratedText(text = answer, state = GenerationState.COMPLETED),
                            delivery = AssistantDelivery(deliveredText = delivered, state = DeliveryState.COMPLETED),
                        ),
            )
        events += "reply-end"
        // The loop reports the spoken reply and the settled conversation; the
        // assistant only returns them, exactly as the orchestrator-backed
        // implementation does.
        return VoiceAssistantReply(
            conversation = withAssistant,
            generatedText = answer,
            deliveredText = delivered,
            outcome = failure?.let { TurnOutcome.ProviderError(it) } ?: TurnOutcome.Completed,
            failure = failure,
        )
    }

    override fun interrupt(onsetAtNanos: Long) {
        // Records only. A real assistant is released by its own cancellation; the
        // gate belongs to the test, so the loop can be observed while the reply is
        // still outstanding.
        interrupts += onsetAtNanos
        events += "interrupt"
    }

    private fun Conversation.with(turn: UserTurn): Conversation = copy(turns = turns + turn)
}
