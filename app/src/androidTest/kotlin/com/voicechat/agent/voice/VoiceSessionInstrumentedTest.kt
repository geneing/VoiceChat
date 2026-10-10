package com.voicechat.agent.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.vad.EndpointReason
import com.voicechat.agent.vad.TurnDetectionEvent
import com.voicechat.agent.vad.VadReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the M24 [VoiceSessionCoordinator].
 *
 * It runs the **real** STT-only coordinator (capture → endpoint → transcript
 * persistence) on the device with inline deterministic fakes and an in-memory
 * repository. It uses no network, credential, microphone, or real STT, so it is
 * a structural smoke check, not a voice-quality measurement (see `Tests.md`).
 *
 * Compiled by `:app:assembleDebugAndroidTest`; run only on a device with
 * `:app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSessionInstrumentedTest {
    @Test
    fun aFakeVoiceSessionPersistsTheTranscriptWithoutGeneratingAReply() =
        runBlocking {
            val repository = InlineRepository()
            val conversation =
                Conversation(
                    id = ConversationId("device-voice-conversation"),
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                )
            val coordinator =
                VoiceSessionCoordinator(
                    audioInput = InlineAudioInput(),
                    repository = repository,
                    speechToText = InlineSpeechToText(),
                    turnDetector = InlineDetector(),
                    dispatcher = Dispatchers.Default,
                    wallClock = { 2L },
                )

            coroutineScope {
                val session = launch(Dispatchers.Default) { coordinator.run(conversation) }
                withTimeout(10_000L) {
                    while (repository.load(conversation.id)?.turns?.size != 1) {
                        delay(20L)
                    }
                }
                session.cancelAndJoin()
            }

            val persisted = repository.load(conversation.id)
            assertNotNull(persisted)
            assertEquals(1, persisted!!.turns.size)
            assertEquals("hello voice", (persisted.turns.single() as com.voicechat.agent.domain.UserTurn).transcript.text)
        }

    private class InlineDetector : VoiceTurnDetector {
        override fun detect(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent> =
            flow {
                emit(TurnDetectionEvent.Activity(SpeechActivity.SPEECH_STARTED, VadReason.ONSET_RMS, 0L))
                emit(TurnDetectionEvent.Endpointed(EndpointReason.SILENCE_CAP, 1200L))
                awaitCancellation()
            }
    }

    private class InlineSpeechToText(
        override val engineId: EngineId = EngineId("device-inline-stt"),
    ) : SpeechToText {
        override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
            flow {
                audio.collect { }
                emit(SttEvent.Result(Transcript.final("hello voice")))
            }

        override suspend fun close() = Unit
    }

    private class InlineAudioInput : AudioInput {
        override val format: AudioFormat = AudioFormat.MONO_16_KHZ

        override fun frames(): Flow<AudioFrame> =
            flowOf(
                AudioFrame(format, ShortArray(160), capturedAtNanos = 0L),
                AudioFrame(format, ShortArray(160), capturedAtNanos = 0L),
            )

        override suspend fun close() = Unit
    }

    private class InlineRepository : ConversationRepository {
        private val conversations = MutableStateFlow<Map<ConversationId, Conversation>>(emptyMap())

        override fun observeConversations(): Flow<List<ConversationSummary>> =
            conversations.map { stored ->
                stored.values.map {
                    ConversationSummary(id = it.id, updatedAtEpochMillis = it.updatedAtEpochMillis, turnCount = it.turns.size)
                }
            }

        override suspend fun load(id: ConversationId): Conversation? = conversations.value[id]

        override suspend fun save(conversation: Conversation) {
            conversations.value = conversations.value + (conversation.id to conversation)
        }

        override suspend fun saveTurn(
            conversation: Conversation,
            turn: Turn,
        ) {
            conversations.value =
                conversations.value +
                (
                    conversation.id to
                        (
                            conversations.value[conversation.id]?.let { existing ->
                                conversation.copy(turns = existing.turns.filterNot { it.id == turn.id } + turn)
                            } ?: conversation
                        )
                )
        }

        override suspend fun delete(id: ConversationId) {
            conversations.value = conversations.value - id
        }
    }
}
