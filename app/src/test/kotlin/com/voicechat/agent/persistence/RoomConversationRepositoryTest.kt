package com.voicechat.agent.persistence

import android.content.Context
import androidx.room.Room
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.domain.context.ModelContextBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var repository: RoomConversationRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ConversationDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomConversationRepository(database.conversationDao())
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
    }

    private fun userTurn(
        id: String,
        text: String,
        isFinal: Boolean = true,
        revision: Int = 0,
        languageTag: String? = null,
        confidence: Float? = null,
    ): UserTurn =
        UserTurn(
            id = TurnId(id),
            transcript =
                Transcript(
                    text = text,
                    revision = TranscriptRevision(revision),
                    isFinal = isFinal,
                    languageTag = languageTag,
                    confidence = confidence,
                ),
            source = UserTurnSource.VOICE,
        )

    private fun assistantTurn(
        id: String,
        generated: String,
        delivered: String,
        generationState: GenerationState = GenerationState.COMPLETED,
        deliveryState: DeliveryState = DeliveryState.COMPLETED,
    ): AssistantTurn =
        AssistantTurn(
            id = TurnId(id),
            generated = GeneratedText(generated, generationState),
            delivery = AssistantDelivery(delivered, deliveryState),
        )

    private fun conversation(
        id: String,
        updatedAt: Long,
        title: String? = null,
        turns: List<Turn> = emptyList(),
    ): Conversation =
        Conversation(
            id = ConversationId(id),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = updatedAt,
            title = title,
            turns = turns,
        )

    @Test
    fun saveThenLoadRoundTripsEveryTurnField() =
        runTest {
            val original =
                conversation(
                    id = "conversation-1",
                    updatedAt = 100L,
                    title = "Dinner plans",
                    turns =
                        listOf(
                            userTurn(
                                id = "u1",
                                text = "book a table for two",
                                revision = 3,
                                languageTag = "en-US",
                                confidence = 0.87f,
                            ),
                            assistantTurn(
                                id = "a1",
                                generated = "Sure, for what time?",
                                delivered = "Sure, for",
                                deliveryState = DeliveryState.INTERRUPTED,
                                generationState = GenerationState.CANCELLED,
                            ),
                        ),
                )

            repository.save(original)

            assertEquals(original, repository.load(ConversationId("conversation-1")))
        }

    @Test
    fun summariesAreOrderedNewestFirstAndCountTurns() =
        runTest {
            repository.save(conversation("older", updatedAt = 10L, turns = listOf(userTurn("o1", "old"))))
            repository.save(
                conversation(
                    "newer",
                    updatedAt = 20L,
                    turns = listOf(userTurn("n1", "newer one"), userTurn("n2", "newer two")),
                ),
            )

            val summaries = repository.observeConversations().first()

            assertEquals(listOf(ConversationId("newer"), ConversationId("older")), summaries.map { it.id })
            assertEquals(2, summaries.first().turnCount)
            assertEquals(1, summaries.last().turnCount)
        }

    @Test
    fun saveReplacesTheTurnListAndRewritesPositions() =
        runTest {
            repository.save(
                conversation("conversation-1", updatedAt = 10L, turns = listOf(userTurn("u1", "first"), userTurn("u2", "second"))),
            )

            repository.save(conversation("conversation-1", updatedAt = 11L, turns = listOf(userTurn("u3", "only"))))

            val loaded = repository.load(ConversationId("conversation-1"))
            assertEquals(listOf(TurnId("u3")), loaded?.turns?.map { it.id })
            assertEquals(1, database.conversationDao().turnCount("conversation-1"))
        }

    @Test
    fun deletingAConversationCascadesToItsTurns() =
        runTest {
            repository.save(conversation("conversation-1", updatedAt = 10L, turns = listOf(userTurn("u1", "hello"))))

            repository.delete(ConversationId("conversation-1"))

            assertNull(repository.load(ConversationId("conversation-1")))
            assertEquals(0, database.conversationDao().turnCount("conversation-1"))
            assertTrue(repository.observeConversations().first().isEmpty())
        }

    @Test
    fun deletingAMissingConversationIsANoOp() =
        runTest {
            repository.delete(ConversationId("does-not-exist"))
        }

    @Test
    fun loadingAMissingConversationReturnsNull() =
        runTest {
            assertNull(repository.load(ConversationId("does-not-exist")))
        }

    @Test
    fun renameThroughTheDomainCopyPersistsTitleAndUpdateTime() =
        runTest {
            repository.save(conversation("conversation-1", updatedAt = 10L, turns = listOf(userTurn("u1", "hello"))))
            val loaded = repository.load(ConversationId("conversation-1"))!!

            repository.save(loaded.renamed(title = "Renamed", updatedAtEpochMillis = 50L))

            val renamed = repository.load(ConversationId("conversation-1"))!!
            assertEquals("Renamed", renamed.title)
            assertEquals(50L, renamed.updatedAtEpochMillis)
            assertEquals(
                ConversationId("conversation-1"),
                repository
                    .observeConversations()
                    .first()
                    .single()
                    .id,
            )
        }

    @Test
    fun removingATurnThroughTheDomainCopyPersists() =
        runTest {
            repository.save(
                conversation("conversation-1", updatedAt = 10L, turns = listOf(userTurn("u1", "first"), userTurn("u2", "second"))),
            )
            val loaded = repository.load(ConversationId("conversation-1"))!!

            repository.save(loaded.withoutTurn(TurnId("u1")))

            assertEquals(listOf(TurnId("u2")), repository.load(ConversationId("conversation-1"))?.turns?.map { it.id })
        }

    @Test
    fun processRestartRestoresPersistedConversationFromDisk() =
        runTest {
            val databaseName = "restart-round-trip.db"
            context.deleteDatabase(databaseName)
            val original =
                conversation(
                    id = "conversation-1",
                    updatedAt = 100L,
                    title = "Persisted",
                    turns = listOf(userTurn("u1", "survive the restart")),
                )

            val first = openFileDatabase(databaseName)
            try {
                RoomConversationRepository(first.conversationDao()).save(original)
            } finally {
                first.close()
            }

            val reopened = openFileDatabase(databaseName)
            try {
                val restored = RoomConversationRepository(reopened.conversationDao()).load(ConversationId("conversation-1"))
                assertEquals(original, restored)
            } finally {
                reopened.close()
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun interruptedTurnsSurviveRestartAndRecoverTruthfully() =
        runTest {
            val databaseName = "restart-interrupted.db"
            context.deleteDatabase(databaseName)
            val original =
                conversation(
                    id = "conversation-1",
                    updatedAt = 100L,
                    turns =
                        listOf(
                            userTurn("u1", "tell me a story"),
                            AssistantTurn.pending(TurnId("a1")),
                            assistantTurn(
                                id = "a2",
                                generated = "the complete answer",
                                delivered = "the complete",
                                generationState = GenerationState.CANCELLED,
                                deliveryState = DeliveryState.INTERRUPTED,
                            ),
                        ),
                )

            val first = openFileDatabase(databaseName)
            try {
                RoomConversationRepository(first.conversationDao()).save(original)
            } finally {
                first.close()
            }

            val reopened = openFileDatabase(databaseName)
            try {
                val loaded = RoomConversationRepository(reopened.conversationDao()).load(ConversationId("conversation-1"))!!
                // Storage kept the mid-turn state truthfully...
                val pending = loaded.turns[1] as AssistantTurn
                assertEquals(GenerationState.IN_PROGRESS, pending.generated.state)
                assertEquals(DeliveryState.NOT_STARTED, pending.delivery.state)

                // ...and recovery after the restart reconciles it without inventing output.
                val recovered = loaded.reconcileAfterProcessDeath()
                val recoveredPending = recovered.turns[1] as AssistantTurn
                assertEquals(GenerationState.CANCELLED, recoveredPending.generated.state)
                assertEquals(DeliveryState.INTERRUPTED, recoveredPending.delivery.state)
                val interrupted = recovered.turns[2] as AssistantTurn
                assertEquals("the complete", interrupted.delivery.deliveredText)
                assertEquals("the complete answer", interrupted.generated.text)
            } finally {
                reopened.close()
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun provisionalTranscriptIsStoredTruthfullyAndDroppedByRecovery() =
        runTest {
            repository.save(
                conversation(
                    "conversation-1",
                    updatedAt = 10L,
                    turns = listOf(userTurn(id = "u1", text = "helo ther", isFinal = false, revision = 2)),
                ),
            )

            val loaded = repository.load(ConversationId("conversation-1"))!!
            val provisional = loaded.turns.single() as UserTurn
            assertEquals(false, provisional.transcript.isFinal)
            assertEquals(TranscriptRevision(2), provisional.transcript.revision)
            assertTrue(loaded.reconcileAfterProcessDeath().isEmpty)
        }

    @Test
    fun persistenceFailureIsReportedAsATypedError() =
        runTest {
            repository.save(conversation("conversation-1", updatedAt = 10L))
            database.close()

            val thrown =
                assertThrows(VoiceAgentException::class.java) {
                    runBlocking { repository.save(conversation("conversation-2", updatedAt = 11L)) }
                }

            assertEquals(ErrorCode.PERSISTENCE_FAILED, thrown.error.code)
        }

    @Test
    fun saveTurnAppendsOneTurnWithoutRewritingSiblings() =
        runTest {
            repository.save(
                conversation(
                    "conversation-1",
                    updatedAt = 10L,
                    turns = listOf(userTurn("u1", "first"), userTurn("u2", "second")),
                ),
            )

            val appended =
                conversation(
                    "conversation-1",
                    updatedAt = 11L,
                    turns =
                        listOf(
                            userTurn("u1", "first"),
                            userTurn("u2", "second"),
                            assistantTurn("a1", generated = "new reply", delivered = ""),
                        ),
                )
            repository.saveTurn(appended, appended.turns.last())

            val loaded = repository.load(ConversationId("conversation-1"))!!
            assertEquals(listOf(TurnId("u1"), TurnId("u2"), TurnId("a1")), loaded.turns.map { it.id })
            assertEquals(3, database.conversationDao().turnCount("conversation-1"))
            assertEquals(11L, loaded.updatedAtEpochMillis)
        }

    @Test
    fun saveTurnUpdatesAnExistingTurnInPlace() =
        runTest {
            repository.save(
                conversation(
                    "conversation-1",
                    updatedAt = 10L,
                    turns = listOf(userTurn("u1", "first"), assistantTurn("a1", generated = "partial", delivered = "")),
                ),
            )

            val settled = assistantTurn("a1", generated = "complete reply", delivered = "complete reply")
            val updated =
                conversation(
                    "conversation-1",
                    updatedAt = 12L,
                    turns = listOf(userTurn("u1", "first"), settled),
                )
            repository.saveTurn(updated, settled)

            val loaded = repository.load(ConversationId("conversation-1"))!!
            assertEquals(listOf(TurnId("u1"), TurnId("a1")), loaded.turns.map { it.id })
            assertEquals(settled, loaded.turns.last())
            assertEquals(2, database.conversationDao().turnCount("conversation-1"))
        }

    @Test
    fun buildingContextForANewConversationNeverIncludesAnOlderOne() =
        runTest {
            repository.save(
                conversation(
                    "older",
                    updatedAt = 10L,
                    turns = listOf(userTurn("o1", "SECRET-OLDER-TOPIC")),
                ),
            )
            repository.save(
                conversation(
                    "fresh",
                    updatedAt = 20L,
                    turns = listOf(userTurn("f1", "a brand new question")),
                ),
            )

            val fresh = repository.load(ConversationId("fresh"))!!
            val window = ModelContextBuilder().build(fresh)

            assertEquals(ConversationId("fresh"), window.conversationId)
            assertEquals(listOf("a brand new question"), window.messages.map { it.text })
            assertTrue(window.messages.none { it.text.contains("SECRET-OLDER-TOPIC") })
            // The full local history is preserved even though it was not sent.
            assertNotNull(repository.load(ConversationId("older")))
        }

    private fun openFileDatabase(name: String): ConversationDatabase =
        Room
            .databaseBuilder(context, ConversationDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
}
