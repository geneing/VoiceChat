package com.voicechat.agent.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database holding conversations and turns.
 *
 * **Versioning.** Version 1 is the initial schema, exported to
 * `app/schemas/com.voicechat.agent.persistence.ConversationDatabase/1.json`.
 * There are no migrations yet; adding a version must add a `Migration` and a
 * schema-to-schema test (see `docs/persistence.md`).
 *
 * The database file lives in app-private storage (`databases/`). It is excluded
 * from cloud backup and device transfer by
 * `src/main/res/xml/data_extraction_rules.xml` and `android:allowBackup="false"`.
 */
@Database(
    entities = [ConversationEntity::class, TurnEntity::class],
    version = ConversationDatabase.VERSION,
    exportSchema = true,
)
internal abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        /** Current schema version. Bump together with a new migration. */
        const val VERSION: Int = 1

        private const val DATABASE_NAME = "voicechat-conversations.db"

        /** Builds the app's single database instance. */
        fun create(context: Context): ConversationDatabase =
            Room.databaseBuilder(context.applicationContext, ConversationDatabase::class.java, DATABASE_NAME).build()
    }
}
