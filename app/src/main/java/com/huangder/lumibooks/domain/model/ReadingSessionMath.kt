package com.huangder.lumibooks.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReadingSessionDayContribution(
    val date: String,
    val duration: Long,
    val startTime: Long,
    val endTime: Long
)

/**
 * Attributes effective reading time to calendar days by wall-clock overlap.
 * The split is deterministic so saving, syncing and deletion all use the same buckets.
 */
fun splitReadingSessionDuration(
    startWallMs: Long,
    endWallMs: Long,
    durationMs: Long,
    zone: ZoneId = ZoneId.systemDefault()
): List<ReadingSessionDayContribution> {
    if (durationMs <= 0L) return emptyList()
    val safeEnd = maxOf(startWallMs, endWallMs)
    val startDate = Instant.ofEpochMilli(startWallMs).atZone(zone).toLocalDate()
    if (safeEnd <= startWallMs) {
        return listOf(
            ReadingSessionDayContribution(
                date = startDate.toString(),
                duration = durationMs,
                startTime = startWallMs,
                endTime = safeEnd
            )
        )
    }

    val totalWallMs = safeEnd - startWallMs
    val endDate = Instant.ofEpochMilli(safeEnd).atZone(zone).toLocalDate()
    val result = mutableListOf<ReadingSessionDayContribution>()
    var date: LocalDate = startDate
    while (!date.isAfter(endDate)) {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val overlapStart = maxOf(startWallMs, dayStart)
        val overlapEnd = minOf(safeEnd, dayEnd)
        if (overlapEnd > overlapStart) {
            val share = (durationMs.toDouble() * (overlapEnd - overlapStart) / totalWallMs).toLong()
            result += ReadingSessionDayContribution(
                date = date.toString(),
                duration = share.coerceAtLeast(0L),
                startTime = overlapStart,
                endTime = overlapEnd
            )
        }
        date = date.plusDays(1)
    }

    if (result.isEmpty()) {
        return listOf(
            ReadingSessionDayContribution(startDate.toString(), durationMs, startWallMs, safeEnd)
        )
    }
    val allocated = result.sumOf { it.duration }
    if (allocated != durationMs) {
        result[result.lastIndex] = result.last().copy(
            duration = (result.last().duration + durationMs - allocated).coerceAtLeast(0L)
        )
    }
    return result
}
