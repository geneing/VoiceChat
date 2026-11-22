package com.voicechat.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatEntity::class, MessageEntity::class, MemoryFactEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    abstract fun messageDao(): MessageDao

    abstract fun memoryDao(): MemoryDao

    companion object {

        /**
         * Adds device memory. The build falls back to a destructive migration
         * only for unhandled versions, so this table must be created here: a
         * version bump without a migration would wipe every chat.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_facts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`subject` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`keywords` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`pinned` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "`useCount` INTEGER NOT NULL, " +
                        "`sourceChatId` TEXT, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_facts_kind_subject` " +
                        "ON `memory_facts` (`kind`, `subject`)",
                )
            }
        }

        fun build(context: Context, name: String = "voicechat.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
    }
}
