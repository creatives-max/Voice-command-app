package com.voicecontrol.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FlowDao {
    @Query("SELECT * FROM flows ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<FlowEntity>>

    @Query("SELECT * FROM flows WHERE appPackage = :appPackage")
    suspend fun byPackage(appPackage: String): List<FlowEntity>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun byId(id: String): FlowEntity?

    @Query("SELECT * FROM flows WHERE synced = 0")
    suspend fun unsynced(): List<FlowEntity>

    @Upsert
    suspend fun upsert(flow: FlowEntity)

    @Query("DELETE FROM flows WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM flows")
    suspend fun clear()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE sessionId = :id")
    suspend fun byId(id: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE synced = 0 ORDER BY startedAtMillis")
    suspend fun unsynced(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity)

    @Query("UPDATE history SET synced = 1 WHERE sessionId IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM history WHERE sessionId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}
