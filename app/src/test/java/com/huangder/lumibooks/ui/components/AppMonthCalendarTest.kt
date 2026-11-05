package com.huangder.lumibooks.ui.components

import java.time.DayOfWeek
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class AppMonthCalendarTest {
    @Test fun leapYearAndShortMonthsHaveExactlyTheirRealDates() {
        for (month in listOf(YearMonth.of(2024, 2), YearMonth.of(2026, 2), YearMonth.of(2026, 4), YearMonth.of(2026, 12))) {
            val cells = monthCalendarCells(month, DayOfWeek.SUNDAY)
            assertEquals(month.lengthOfMonth(), cells.filterNotNull().size)
            assertEquals(0, cells.size % 7)
            assertEquals((1..month.lengthOfMonth()).toList(), cells.filterNotNull().map { it.dayOfMonth })
        }
    }

    @Test fun localeWeekStartAndYearTransitionsStayAligned() {
        val january = YearMonth.of(2027, 1)
        assertEquals(5, monthCalendarCells(january, DayOfWeek.SUNDAY).takeWhile { it == null }.size)
        assertEquals(4, monthCalendarCells(january, DayOfWeek.MONDAY).takeWhile { it == null }.size)
        assertEquals(YearMonth.of(2026, 12), january.minusMonths(1))
        assertEquals(january, january.minusMonths(1).plusMonths(1))
    }
}
