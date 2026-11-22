package com.voicechat.assistant.tutor

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voicechat.assistant.data.model.Message
import com.voicechat.assistant.data.model.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

private val Context.tutorDataStore by preferencesDataStore("spanish_tutor_progress")

/** Independent from assistant facts and from the lifetime of any single lesson/chat. */
class TutorStore internal constructor(private val data: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.tutorDataStore)
    private val key = stringPreferencesKey("progress")
    val progress = data.data.map { prefs ->
        prefs[key]?.let { TutorProgress.json.decodeFromString<TutorProgress>(it) } ?: TutorProgress()
    }

    suspend fun snapshot(): TutorProgress = progress.first()

    suspend fun update(args: JsonObject, turnId: String? = null) = data.edit { prefs ->
        val old = prefs[key]?.let { TutorProgress.json.decodeFromString<TutorProgress>(it) } ?: TutorProgress()
        val evaluated = turnId ?: old.recentDialog.lastOrNull { it.role == "user" }?.id
        if (evaluated != null && evaluated in old.evaluatedTurnIds) return@edit
        val updated = old.updated(args, System.currentTimeMillis())
        prefs[key] = TutorProgress.json.encodeToString(updated.copy(
            evaluatedTurnIds = if (evaluated == null) old.evaluatedTurnIds else (old.evaluatedTurnIds + evaluated).takeLast(60),
        ))
    }.let { Unit }

    suspend fun record(message: Message) {
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return
        data.edit { prefs ->
            val old = prefs[key]?.let { TutorProgress.json.decodeFromString<TutorProgress>(it) } ?: TutorProgress()
            prefs[key] = TutorProgress.json.encodeToString(old.withTurn(
                TutorTurn(message.id, message.role.name.lowercase(), message.text),
            ))
        }
    }

    suspend fun clear() { data.edit { it.remove(key) } }
}
