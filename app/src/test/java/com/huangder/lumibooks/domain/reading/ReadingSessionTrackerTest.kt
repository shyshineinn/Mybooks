package com.huangder.lumibooks.domain.reading

import com.huangder.lumibooks.domain.model.MIN_EFFECTIVE_READING_SESSION_MS
import com.huangder.lumibooks.domain.model.MIN_READING_SESSION_MS
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.domain.model.splitReadingSessionDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReadingSessionTrackerTest {
    private var wall = 1_000_000L
    private var monotonic = 0L

    private fun tracker() = ReadingSessionTracker(
        wallClock = { wall },
        monotonicClock = { monotonic },
        newSessionKey = { "session-${monotonic}" }
    )

    @Test fun fiveSecondsIsRawButNotEffective() {
        val tracker = tracker()
        tracker.begin("book", "EPUB")
        monotonic += 5_000
        val snapshot = tracker.finish()
        assertNotNull(snapshot)
        assertEquals(5_000L, snapshot!!.duration)
        val session = ReadingSession(
            bookId = "book", date = snapshot.date, startTime = snapshot.startWallTime,
            endTime = snapshot.endWallTime, duration = snapshot.duration
        )
        assertTrue(snapshot.duration >= MIN_READING_SESSION_MS)
        assertFalse(session.isEffective)
        assertTrue(MIN_EFFECTIVE_READING_SESSION_MS > snapshot.duration)
    }

    @Test fun twentySecondsAndSixtySecondsAreEffective() {
        val twenty = ReadingSession(bookId = "book", date = "2026-11-01", startTime = 0, endTime = 20_000, duration = 20_000)
        val sixty = twenty.copy(duration = 60_000, endTime = 60_000)
        assertTrue(twenty.isEffective)
        assertTrue(sixty.isEffective)
    }

    @Test fun shortBackgroundVisitContinuesTheSameSession() {
        val tracker = tracker()
        tracker.begin("book", "EPUB")
        monotonic += 10_000
        val paused = tracker.pause()
        assertEquals(10_000L, paused!!.duration)
        val key = paused.sessionKey

        wall += 60_000
        monotonic += 60_000
        val resumed = tracker.resume("book", "EPUB")
        assertTrue(resumed.continued)
        assertEquals(key, resumed.snapshot!!.sessionKey)
        assertEquals(10_000L, resumed.snapshot.duration)

        monotonic += 5_000
        val finished = tracker.finish()
        assertEquals(15_000L, finished!!.duration)
    }

    @Test fun longBackgroundVisitEndsTheSessionAndStartsAnother() {
        val tracker = tracker()
        tracker.begin("book", "EPUB")
        monotonic += 10_000
        val paused = tracker.pause()!!
        wall += 6 * 60_000
        monotonic += 6 * 60_000

        val resumed = tracker.resume("book", "EPUB")
        assertFalse(resumed.continued)
        assertEquals(paused.sessionKey, resumed.previous!!.sessionKey)
        assertEquals(10_000L, resumed.previous.duration)
        assertEquals(0L, resumed.snapshot!!.duration)
        assertTrue(resumed.snapshot.sessionKey != paused.sessionKey)
    }

    @Test fun repeatedFinishReturnsOneSessionOnly() {
        val tracker = tracker()
        tracker.begin("book", "EPUB")
        monotonic += 30_000
        assertNotNull(tracker.finish())
        assertNull(tracker.finish())
    }

    @Test fun crossMidnightUsesStartDateAndSplitsDailyContribution() {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = LocalDate.of(2026, 11, 1).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 2).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        val split = splitReadingSessionDuration(start, end, 20 * 60_000L, zone)
        assertEquals(listOf("2026-11-01", "2026-11-02"), split.map { it.date })
        assertEquals(20 * 60_000L, split.sumOf { it.duration })
        assertEquals(10 * 60_000L, split[0].duration)
        assertEquals(10 * 60_000L, split[1].duration)
    }

    @Test fun differentFormatDoesNotContinueTheSession() {
        val tracker = tracker()
        tracker.begin("book", "EPUB")
        monotonic += 30_000
        val resumed = tracker.resume("book", "TXT")
        assertFalse(resumed.continued)
        assertNotNull(resumed.previous)
        assertTrue(resumed.snapshot!!.sessionKey != resumed.previous!!.sessionKey)
    }
}
