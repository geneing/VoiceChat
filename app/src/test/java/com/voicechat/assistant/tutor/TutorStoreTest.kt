package com.voicechat.assistant.tutor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.voicechat.assistant.data.model.Message
import com.voicechat.assistant.data.model.MessageRole
import com.voicechat.assistant.data.model.MessageKind
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TutorStoreTest {
    // Test our mapping against the DataStore contract. Disk replacement is verified on Android;
    // DataStore's JVM File.renameTo cannot replace existing files on this Windows host.
    private class PreferencesStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }

    @Test fun `progress survives a new store instance and resets to defaults`() = runTest {
        val data = PreferencesStore()
        val store = TutorStore(data)
        store.update(Json.parseToJsonElement("""{"summary":"Practiced ordering tacos", "vocabulary":[{"topic":"quisiera","outcome":"needs_work"}]}""").jsonObject)
        val message = Message(id = "turn1", chatId = "lesson1", role = MessageRole.USER,
            kind = MessageKind.TEXT, text = "Quisiera dos tacos", attachments = emptyList(), createdAt = 1)
        store.record(message)
        val reopened = TutorStore(data)
        assertEquals("Practiced ordering tacos", reopened.snapshot().summary)
        assertEquals("quisiera", reopened.snapshot().vocabulary.single().topic)
        assertEquals("Quisiera dos tacos", reopened.snapshot().recentDialog.single().text)
        reopened.clear()
        assertEquals(TutorProgress(), store.snapshot())
    }

    @Test fun `independent tutor stores never share progress`() = runTest {
        val first = TutorStore(PreferencesStore())
        val second = TutorStore(PreferencesStore())
        first.update(Json.parseToJsonElement("""{"summary":"Ser versus estar needs work"}""").jsonObject)
        assertEquals("", second.snapshot().summary)
    }

    @Test fun `tool and background recap cannot double count the same learner answer`() = runTest {
        val store = TutorStore(PreferencesStore())
        store.record(Message(id = "answer1", chatId = "lesson1", role = MessageRole.USER,
            kind = MessageKind.TEXT, text = "Quiero tacos", attachments = emptyList(), createdAt = 1))
        val update = Json.parseToJsonElement("""{"vocabulary":[{"topic":"querer","outcome":"correct"}]}""").jsonObject
        store.update(update)
        store.update(update, "answer1")
        assertEquals(1, store.snapshot().vocabulary.single().successes)
        assertEquals(1, store.snapshot().assessedTurns)
    }
}
