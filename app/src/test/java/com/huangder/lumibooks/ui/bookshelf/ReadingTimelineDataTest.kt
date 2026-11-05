package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.data.sync.MyBooksReadStatus
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.domain.model.ReadingStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReadingTimelineDataTest {
    private fun meta(logs: JSONArray = JSONArray(), count: Int = 0, start: String = "", finish: String = "") =
        ServerBookMeta.fromJson(JSONObject().put("title", "Test").put("status", "wish")
            .put("read_logs", logs).put("read_count", count).put("start_date", start).put("finish_date", finish).toString(), 1)
    private fun row(start: String = "", finish: String = "") = JSONObject().put("start", start).put("finish", finish)

    @Test fun unreadBookHasNoInventedEventsOrRereadAction() {
        val book = meta()
        assertTrue(readingTimelineEvents(book).isEmpty())
        assertNull(nextReadingRound(book))
        assertNull(pendingReadingRound(book))
    }

    @Test fun firstCompletionAndSecondStartProduceTheRequestedThreeEventPreview() {
        val book = meta(JSONArray().put(row("2026-10-27", "2026-11-01")).put(row("2026-11-04")), 1)
        val events = readingTimelineEvents(book)
        assertEquals(listOf(2, 1, 1), events.map { it.round })
        assertEquals(listOf(ReadingTimelineEventType.START, ReadingTimelineEventType.FINISH,
            ReadingTimelineEventType.START), events.map { it.type })
        assertEquals(3, readingTimelineGapDays(events[0], events[1]))
        assertEquals(5, readingTimelineGapDays(events[1], events[2]))
        assertNull(nextReadingRound(book))
        assertEquals(2, pendingReadingRound(book))
    }

    @Test fun completedSecondRoundRetainsItsDatesAndOriginalRoundNumbers() {
        val first = MyBooksReadStatus.transition(emptyList(), ReadingStatus.READING, "2026-10-27")
        val done = MyBooksReadStatus.transition(first.logs, ReadingStatus.DONE, "2026-11-01")
        val second = MyBooksReadStatus.startNextReading(done.logs, "2026-11-04")
        val finished = MyBooksReadStatus.toggle(second.logs, true, "2026-11-07")
        val book = meta(MyBooksReadStatus.logsToJson(finished.logs), 2)
        val events = readingTimelineEvents(book)
        assertEquals(listOf(2, 2, 1, 1), events.map { it.round })
        assertEquals(listOf("2026-11-07", "2026-11-04", "2026-11-01", "2026-10-27"), events.map { it.date.toString() })
        assertEquals(3, events.take(3).size)
        assertEquals(3, nextReadingRound(book))
        assertNull(pendingReadingRound(book))
    }

    @Test fun sameDayRoundsStaySeparateAndPendingRereadComesAfterTheFirstCompletion() {
        val logs = JSONArray().put(row("2026-11-04", "2026-11-04")).put(row("2026-11-04"))
        val pending = meta(logs, 1)
        assertEquals(2, readingTimelineEvents(pending).first().round)
        assertEquals(ReadingTimelineEventType.START, readingTimelineEvents(pending).first().type)
        logs.getJSONObject(1).put("finish", "2026-11-04")
        val completed = readingTimelineEvents(meta(logs, 2))
        assertEquals(4, completed.size)
        assertEquals(listOf(2, 2, 1, 1), completed.map { it.round })
        assertEquals(ReadingTimelineEventType.FINISH, completed.first().type)
        assertEquals(0, readingTimelineGapDays(completed[0], completed[1]))
    }

    @Test fun missingDatesDoNotCreateEventsAndLegacyDatesKeepTheirKnownRound() {
        val partial = readingTimelineEvents(meta(JSONArray().put(row(finish = "2026-11-01"))))
        assertEquals(1, partial.size)
        assertEquals(ReadingTimelineEventType.FINISH, partial.single().type)
        val legacy = readingTimelineEvents(meta(count = 4, finish = "2026-11-01"))
        assertEquals(4, legacy.single().round)
        assertEquals(LocalDate.of(2026, 11, 1), legacy.single().date)
        assertTrue(readingTimelineEvents(meta(JSONArray().put(row("bad-date")))).isEmpty())
    }

    @Test fun legacyPreviewDoesNotOfferDeletionOfAnUntrackedStart() {
        val legacy = meta(count = 3, start = "2026-11-04", finish = "2026-11-01")
        assertEquals(2, readingTimelineEvents(legacy).size)
        assertNull(nextReadingRound(legacy))
        assertNull(pendingReadingRound(legacy))
    }
}
