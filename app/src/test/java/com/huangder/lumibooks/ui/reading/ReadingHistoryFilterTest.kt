package com.huangder.lumibooks.ui.reading

import com.huangder.lumibooks.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReadingHistoryFilterTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test fun defaultAllTimeKeepsTheVisibleLabelAndQueryInSync() {
        val filter = ReadingHistoryFilter()
        val query = filter.toQuery(today)
        assertEquals(R.string.reading_history_period_all, filter.period.labelRes)
        assertNull(query.startDate)
        assertNull(query.endDate)
        assertNull(query.bookId)
        assertNull(query.mode)
    }

    @Test fun weekUsesSundayToSaturday() {
        val query = ReadingHistoryFilter(period = ReadingHistoryPeriod.WEEK).toQuery(today)
        assertEquals("2026-11-01", query.startDate)
        assertEquals("2026-11-07", query.endDate)
    }

    @Test fun monthAndYearUseCalendarBoundaries() {
        val month = ReadingHistoryFilter(period = ReadingHistoryPeriod.MONTH).toQuery(today)
        assertEquals("2026-11-01", month.startDate)
        assertEquals("2026-11-30", month.endDate)
        val year = ReadingHistoryFilter(period = ReadingHistoryPeriod.YEAR).toQuery(today)
        assertEquals("2026-01-01", year.startDate)
        assertEquals("2026-12-31", year.endDate)
    }

    @Test fun exactDateOverridesAPeriodChoice() {
        val query = ReadingHistoryFilter(
            period = ReadingHistoryPeriod.MONTH,
            date = "2026-10-09"
        ).toQuery(today)
        assertEquals("2026-10-09", query.startDate)
        assertEquals("2026-10-09", query.endDate)
    }

    @Test fun modeFilterMapsToTheStoredModeBuckets() {
        assertEquals(
            "scroll",
            ReadingHistoryFilter(mode = ReadingHistoryModeFilter.SCROLL).toQuery(today).mode
        )
        assertEquals(
            "paged",
            ReadingHistoryFilter(mode = ReadingHistoryModeFilter.PAGED).toQuery(today).mode
        )
    }
}
