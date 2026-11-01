package com.huangder.lumibooks.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    indices = [
        Index(value = ["bookId", "startTime"]),
        Index(value = ["startTime"]),
        Index(value = ["date"])
    ]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: String,
    val date: String,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val startChapterIndex: Int = 0,
    val endChapterIndex: Int = 0,
    val startChapterTitle: String? = null,
    val endChapterTitle: String? = null,
    val startProgress: Float = 0f,
    val endProgress: Float = 0f,
    val mode: String = "",
    val sourceDeviceId: String = "",
    val note: String? = null,
    val mood: String? = null,
    val updatedAt: Long = endTime
)
