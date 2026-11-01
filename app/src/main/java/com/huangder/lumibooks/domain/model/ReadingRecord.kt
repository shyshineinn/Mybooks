package com.huangder.lumibooks.domain.model

/** Raw activity below this is kept for the log but never becomes a reading session. */
const val MIN_READING_SESSION_MS = 5_000L
/** Shorter sessions are stored verbatim but excluded from counts and habits. */
const val MIN_EFFECTIVE_READING_SESSION_MS = 20_000L
/** Background gaps up to this length continue the same logical session. */
const val READING_SESSION_RESUME_GRACE_MS = 5 * 60_000L

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
    /** Stable local identity; repeated checkpoints upsert this row instead of adding duplicates. */
    val sessionKey: String = "",
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
    /** Effective time already attributed to [reading_records], per calendar day. */
    val dailyContributions: Map<String, Long> = emptyMap(),
    val contributedDuration: Long = dailyContributions.values.sum(),
    val updatedAt: Long = endTime
) {
    val isEffective: Boolean get() = duration >= MIN_EFFECTIVE_READING_SESSION_MS
}
