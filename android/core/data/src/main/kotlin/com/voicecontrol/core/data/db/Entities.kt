package com.voicecontrol.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached/locally-created flow. [stepsJson] is the serialized `List<FlowStep>`. */
@Entity(tableName = "flows", indices = [Index("appPackage"), Index(value = ["appPackage", "screenSignature"])])
data class FlowEntity(
    @PrimaryKey val id: String,
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val version: Int,
    val stepsJson: String,
    val updatedAtMillis: Long,
    /** True when this row mirrors the server's current version. */
    val synced: Boolean,
)

/** A finished voice session. [screensJson] is the serialized `List<ScreenRecord>`. */
@Entity(tableName = "history", indices = [Index("startedAtMillis")])
data class HistoryEntity(
    @PrimaryKey val sessionId: String,
    val appPackage: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val status: String,
    val language: String,
    val filledCount: Int,
    val screensJson: String,
    val synced: Boolean,
)
