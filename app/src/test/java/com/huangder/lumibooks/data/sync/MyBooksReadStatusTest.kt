package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.domain.model.ReadingStatus
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBooksReadStatusTest {

    private fun log(start: String = "", finish: String = "") = MyBooksReadLog(start, finish)

    @Test
    fun markReadAddsFinishedLogWhenNoneExists() {
        val result = MyBooksReadStatus.toggle(
            currentLogs = emptyList(),
            markRead = true,
            today = "2026-10-25"
        )

        assertEquals("done", result.status)
        assertEquals(listOf(log("2026-10-25", "2026-10-25")), result.logs)
    }

    @Test
    fun markReadFillsTheLatestUnfinishedLog() {
        val result = MyBooksReadStatus.toggle(
            currentLogs = listOf(log("2026-10-01")),
            markRead = true,
            today = "2026-10-25"
        )

        assertEquals("done", result.status)
        assertEquals(listOf(log("2026-10-01", "2026-10-25")), result.logs)
    }

    @Test
    fun markReadKeepsExistingFinishedLogs() {
        val logs = listOf(log("2026-09-01", "2026-09-05"))
        val result = MyBooksReadStatus.toggle(
            currentLogs = logs,
            markRead = true,
            today = "2026-10-25"
        )

        assertEquals("done", result.status)
        assertEquals(logs, result.logs)
    }

    @Test
    fun unmarkClearsTheLatestFinishAndKeepsItsStart() {
        val result = MyBooksReadStatus.toggle(
            currentLogs = listOf(log("2026-10-01", "2026-10-02")),
            markRead = false,
            today = "2026-10-25"
        )

        assertEquals("reading", result.status)
        assertEquals(listOf(log("2026-10-01")), result.logs)
    }

    @Test
    fun unmarkDropsAnEmptyLogAndFallsBackToWish() {
        val result = MyBooksReadStatus.toggle(
            currentLogs = listOf(log(finish = "2026-10-02")),
            markRead = false,
            today = "2026-10-25"
        )

        assertEquals("wish", result.status)
        assertEquals(emptyList<MyBooksReadLog>(), result.logs)
    }

    @Test
    fun unmarkKeepsOtherLogsAndStaysReading() {
        val result = MyBooksReadStatus.toggle(
            currentLogs = listOf(
                log("2026-09-01", "2026-09-05"),
                log("2026-10-01", "2026-10-02")
            ),
            markRead = false,
            today = "2026-10-25"
        )

        assertEquals("reading", result.status)
        assertEquals(
            listOf(log("2026-09-01", "2026-09-05"), log("2026-10-01")),
            result.logs
        )
    }

    @Test
    fun unmarkWithoutLogsFallsBackToWish() {
        val result = MyBooksReadStatus.toggle(
            currentLogs = emptyList(),
            markRead = false,
            today = "2026-10-25"
        )

        assertEquals("wish", result.status)
        assertEquals(emptyList<MyBooksReadLog>(), result.logs)
    }

    @Test
    fun canonicalDropsEmptyRowsAndSortsByDate() {
        val logs = listOf(
            log("", ""),
            log("2026-10-02", ""),
            log("2026-09-01", "2026-09-02")
        )

        assertEquals(
            listOf(log("2026-09-01", "2026-09-02"), log("2026-10-02")),
            MyBooksReadStatus.canonical(logs)
        )
    }

    @Test
    fun parseAndSerializeKeepBothDateFields() {
        val raw = JSONArray()
            .put(org.json.JSONObject().put("start", "2026-09-01").put("finish", "2026-09-02"))
            .put(org.json.JSONObject().put("start", "").put("finish", ""))

        val parsed = MyBooksReadStatus.parseLogs(raw)
        assertEquals(listOf(log("2026-09-01", "2026-09-02")), parsed)
        assertEquals(
            "2026-09-01",
            MyBooksReadStatus.logsToJson(parsed).getJSONObject(0).getString("start")
        )
    }

    @Test
    fun onlyTheFirstWriteReachingFullProgressTriggersAutoMark() {
        assertTrue(MyBooksReadStatus.becameFinished(0.99f, 1f))
        assertFalse(MyBooksReadStatus.becameFinished(1f, 1f))
        assertFalse(MyBooksReadStatus.becameFinished(1f, 0.5f))
        assertFalse(MyBooksReadStatus.becameFinished(0.5f, 0.99f))
    }

    @Test
    fun wishTransitionKeepsAllReadingLogs() {
        val logs = listOf(log("2026-09-01", "2026-09-05"), log("2026-10-10"))

        val result = MyBooksReadStatus.transition(logs, ReadingStatus.WISH, "2026-11-03")

        assertEquals("wish", result.status)
        assertEquals(logs, result.logs)
    }

    @Test
    fun readingTransitionKeepsCompletedHistory() {
        val logs = listOf(log("2026-09-01", "2026-09-05"))

        val result = MyBooksReadStatus.transition(logs, ReadingStatus.READING, "2026-11-03")

        assertEquals("reading", result.status)
        assertEquals(logs, result.logs)
    }

    @Test
    fun doneTransitionAddsCompletionWhenHistoryHasNone() {
        val result = MyBooksReadStatus.transition(emptyList(), ReadingStatus.DONE, "2026-11-03")

        assertEquals("done", result.status)
        assertEquals(listOf(log("2026-11-03", "2026-11-03")), result.logs)
    }

    @Test
    fun doneTransitionFillsPendingLogInsteadOfAddingAnother() {
        val result = MyBooksReadStatus.transition(
            listOf(log("2026-10-01")),
            ReadingStatus.DONE,
            "2026-11-03"
        )

        assertEquals(listOf(log("2026-10-01", "2026-11-03")), result.logs)
    }

    @Test
    fun doneTransitionDoesNotDuplicateAnExistingCompletion() {
        val logs = listOf(log("2026-09-01", "2026-09-05"))
        val result = MyBooksReadStatus.transition(logs, ReadingStatus.DONE, "2026-11-03")

        assertEquals(logs, result.logs)
        assertEquals("done", result.status)
    }

    @Test
    fun doneTransitionDoesNotAddASecondDateForToday() {
        val logs = listOf(log("2026-11-03", "2026-11-03"))
        val result = MyBooksReadStatus.transition(logs, ReadingStatus.DONE, "2026-11-03")

        assertEquals(logs, result.logs)
        assertEquals(1, result.logs.count { it.finish == "2026-11-03" })
    }

    @Test
    fun categoryStatusUsesEffectiveServerStatusOnly() {
        assertEquals(
            ReadingStatus.WISH,
            MyBooksReadStatus.categoryStatus("{}", """{"status":"wish"}""")
        )
        assertEquals(
            ReadingStatus.READING,
            MyBooksReadStatus.categoryStatus(
                """{"status":"reading"}""",
                """{"status":"wish"}"""
            )
        )
        assertEquals(
            ReadingStatus.DONE,
            MyBooksReadStatus.categoryStatus("{}", """{"status":"finished"}""")
        )
        assertEquals(
            ReadingStatus.WISH,
            MyBooksReadStatus.categoryStatus("{}", """{"status":"want"}""")
        )
        assertNull(
            MyBooksReadStatus.categoryStatus("{}", """{"status":"unknown"}""")
        )
        assertNull(MyBooksReadStatus.categoryStatus("{}", "{}"))
    }

    @Test fun unreadFirstReadingAndCompletionKeepTheOriginalStartDate() {
        val unread = MyBooksReadStatus.transition(emptyList(), ReadingStatus.WISH, "2026-10-27")
        assertTrue(unread.logs.isEmpty())
        val started = MyBooksReadStatus.transition(unread.logs, ReadingStatus.READING, "2026-10-27")
        assertEquals(listOf(log("2026-10-27")), started.logs)
        val paused = MyBooksReadStatus.transition(started.logs, ReadingStatus.WISH, "2026-10-28")
        val resumed = MyBooksReadStatus.transition(paused.logs, ReadingStatus.READING, "2026-10-30")
        assertEquals(started.logs, resumed.logs)
        val finished = MyBooksReadStatus.transition(resumed.logs, ReadingStatus.DONE, "2026-11-01")
        assertEquals(listOf(log("2026-10-27", "2026-11-01")), finished.logs)
    }

    @Test fun secondReadingRecordsBothDatesWithoutOverwritingTheFirstRound() {
        val first = listOf(log("2026-10-27", "2026-11-01"))
        val second = MyBooksReadStatus.startNextReading(first, "2026-11-04")
        assertEquals("reading", second.status)
        assertEquals(listOf(first.single(), log("2026-11-04")), second.logs)
        assertEquals(second, MyBooksReadStatus.startNextReading(second.logs, "2026-11-05"))
        val finished = MyBooksReadStatus.transition(second.logs, ReadingStatus.DONE, "2026-11-10")
        assertEquals("done", finished.status)
        assertEquals(listOf(first.single(), log("2026-11-04", "2026-11-10")), finished.logs)
        assertEquals(finished.logs, MyBooksReadStatus.parseLogs(MyBooksReadStatus.logsToJson(finished.logs)))
    }

    @Test fun autoMarkAtFullProgressFinishesThePendingRereadOnlyOnce() {
        val first = log("2026-10-27", "2026-11-01")
        val second = MyBooksReadStatus.startNextReading(listOf(first), "2026-11-04")
        assertTrue(MyBooksReadStatus.becameFinished(0f, 1f))
        val completed = MyBooksReadStatus.toggle(second.logs, markRead = true, today = "2026-11-07")
        assertEquals(listOf(first, log("2026-11-04", "2026-11-07")), completed.logs)
        assertEquals(completed, MyBooksReadStatus.toggle(completed.logs, markRead = true, today = "2026-11-08"))
        assertFalse(MyBooksReadStatus.becameFinished(1f, 1f))
    }

    @Test fun twoRoundsOnTheSameDaySurviveCompletionAndSerialization() {
        val day = "2026-11-04"
        val first = MyBooksReadStatus.transition(emptyList(), ReadingStatus.DONE, day)
        val second = MyBooksReadStatus.startNextReading(first.logs, day)
        assertEquals(listOf(log(day, day), log(day)), second.logs)
        val completed = MyBooksReadStatus.toggle(second.logs, markRead = true, today = day)
        assertEquals(listOf(log(day, day), log(day, day)), completed.logs)
        assertEquals(2, MyBooksReadStatus.parseLogs(MyBooksReadStatus.logsToJson(completed.logs)).size)
    }

    @Test fun abandoningTheRereadRemovesOnlyItsStartDate() {
        val first = log("2026-10-27", "2026-11-01")
        val second = MyBooksReadStatus.startNextReading(listOf(first), "2026-11-04")
        val abandoned = MyBooksReadStatus.abandonCurrentReading(second.logs)
        assertEquals("done", abandoned.status)
        assertEquals(listOf(first), abandoned.logs)
        assertEquals(abandoned, MyBooksReadStatus.abandonCurrentReading(abandoned.logs))
        val restarted = MyBooksReadStatus.startNextReading(abandoned.logs, "2026-11-05")
        assertEquals(listOf(first, log("2026-11-05")), restarted.logs)
    }

    @Test fun abandoningTheFirstUnfinishedReadingReturnsToUnread() {
        val result = MyBooksReadStatus.abandonCurrentReading(listOf(log("2026-11-04")))
        assertEquals("wish", result.status)
        assertTrue(result.logs.isEmpty())
    }
}
