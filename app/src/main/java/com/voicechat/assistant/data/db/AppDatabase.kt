package com.voicechat.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    abstract fun messageDao(): MessageDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "voicechat.db")
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
    }
}
