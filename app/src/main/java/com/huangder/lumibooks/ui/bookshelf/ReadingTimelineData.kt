package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.data.sync.MyBooksReadLog
import com.huangder.lumibooks.data.sync.ReadLogDateSource
import com.huangder.lumibooks.data.sync.readLogDateSource
import com.huangder.lumibooks.domain.model.ReadingStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal enum class ReadingTimelineEventType { START, FINISH }

internal data class ReadingTimelineEvent(
    val round: Int,
    val type: ReadingTimelineEventType,
    val date: LocalDate,
    val source: ReadLogDateSource? = null
)

private data class TimelineReadLog(val start: LocalDate?, val finish: LocalDate?, val source: ReadLogDateSource?)

/** Project recorded dates into UI events without creating missing starts or finishes. */
internal fun readingTimelineEvents(meta: ServerBookMeta): List<ReadingTimelineEvent> {
    fun date(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val snapshot = meta.readLogs.map { MyBooksReadLog(it.start.orEmpty().trim(), it.finish.orEmpty().trim()) }
    val logs = snapshot.map { TimelineReadLog(date(it.start), date(it.finish), readLogDateSource(it, snapshot)) }
        .filter { it.start != null || it.finish != null }
        .sortedWith(compareBy<TimelineReadLog> { it.start ?: it.finish!! }
            .thenBy { it.finish ?: LocalDate.MAX })
    val events = buildList {
        if (logs.isNotEmpty()) {
            logs.forEachIndexed { index, log ->
                log.start?.let { add(ReadingTimelineEvent(index + 1, ReadingTimelineEventType.START, it, log.source)) }
                log.finish?.let { add(ReadingTimelineEvent(index + 1, ReadingTimelineEventType.FINISH, it, log.source)) }
            }
        } else {
            // Legacy metadata can contain only the latest start/finish and completed count.
            val start = date(meta.startDate)
            val finish = date(meta.finishDate)
            val completedRound = meta.readCount.coerceAtLeast(1)
            finish?.let { add(ReadingTimelineEvent(completedRound, ReadingTimelineEventType.FINISH, it)) }
            start?.let {
                val round = if (finish == null || it > finish) meta.readCount.coerceAtLeast(0) + 1 else completedRound
                add(ReadingTimelineEvent(round, ReadingTimelineEventType.START, it))
            }
        }
    }
    // A later round wins same-day ties; within one round completion follows its start.
    return events.sortedWith(compareByDescending<ReadingTimelineEvent> { it.date }
        .thenByDescending { it.round }
        .thenByDescending { it.type == ReadingTimelineEventType.FINISH })
}

internal fun readingTimelineGapDays(newer: ReadingTimelineEvent, older: ReadingTimelineEvent): Int =
    ChronoUnit.DAYS.between(older.date, newer.date).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

/** Only recorded, completed history can offer an explicit next round. */
internal fun nextReadingRound(meta: ServerBookMeta?): Int? {
    if (meta == null || meta.readLogs.none { !it.finish.isNullOrBlank() } ||
        meta.readLogs.any { !it.start.isNullOrBlank() && it.finish.isNullOrBlank() }) return null
    return readingTimelineEvents(meta).maxOfOrNull { it.round }?.plus(1)
}

internal fun pendingReadingRound(meta: ServerBookMeta?): Int? {
    if (meta == null || meta.readLogs.none { !it.start.isNullOrBlank() && it.finish.isNullOrBlank() }) return null
    val events = readingTimelineEvents(meta)
    return events.filter { it.type == ReadingTimelineEventType.START }
        .firstOrNull { start -> events.none { it.round == start.round && it.type == ReadingTimelineEventType.FINISH } }
        ?.round
}

internal fun readingStatusAfterAbandon(meta: ServerBookMeta?): ReadingStatus {
    val pending = meta?.readLogs?.count { !it.start.isNullOrBlank() && it.finish.isNullOrBlank() } ?: 0
    return when {
        pending > 1 -> ReadingStatus.READING
        meta?.readLogs?.any { !it.finish.isNullOrBlank() } == true -> ReadingStatus.DONE
        else -> ReadingStatus.WISH
    }
}
