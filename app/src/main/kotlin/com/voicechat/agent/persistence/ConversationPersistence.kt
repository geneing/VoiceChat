package com.voicechat.agent.persistence

import android.content.Context
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.log.AppLog

/**
 * App-boundary factory for durable conversation storage.
 *
 * Keeps [Context] and the Room database out of the domain and UI layers: the app
 * supplies one long-lived [ConversationRepository] built from its application
 * context, and everything above depends on the M02 contract only.
 */
object ConversationPersistence {
    /** Creates the app's repository over the app-private Room database. */
    fun create(context: Context): ConversationRepository {
        AppLog.i { "persistence: opening conversation repository" }
        return RoomConversationRepository(ConversationDatabase.create(context).conversationDao())
    }
}
