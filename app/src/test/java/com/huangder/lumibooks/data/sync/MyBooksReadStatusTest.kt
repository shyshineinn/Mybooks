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
}
