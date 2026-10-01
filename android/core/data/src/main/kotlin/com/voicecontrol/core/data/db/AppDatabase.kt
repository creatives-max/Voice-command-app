package com.voicecontrol.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FlowEntity::class, HistoryEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "voicecontrol.db"
    }
}
