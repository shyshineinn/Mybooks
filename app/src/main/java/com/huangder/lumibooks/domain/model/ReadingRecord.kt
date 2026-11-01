package com.huangder.lumibooks.domain.model

data class ReadingRecord(
    val id: Long = 0,
    val bookId: String,
    val date: String,
    val duration: Long,
    val startTime: Long,
    val endTime: Long,
    val sourceDeviceId: String = "",
    val updatedAt: Long = endTime
)

data class DailyTotal(
    val date: String,
    val totalDuration: Long
)

/**
 * One continuous reading session. Daily totals stay in [ReadingRecord]; this table
 * keeps the detail the statistics timeline needs and is local to this device.
 */
data class ReadingSession(
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
