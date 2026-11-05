package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadLogDateEditTest {
    private val first = MyBooksReadLog("2026-01-01", "2026-01-20")
    private val second = MyBooksReadLog("2026-02-01", "2026-02-20")
    private fun json(vararg logs: MyBooksReadLog) = JSONArray(logs.map { JSONObject()
        .put("start", it.start).put("finish", it.finish) })
    private fun source(log: MyBooksReadLog = first, logs: List<MyBooksReadLog> = listOf(first, second)) =
        readLogDateSource(log, logs)!!
    private fun rejects(reason: ReadLogDateFailure, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: ReadLogDateException) { assertEquals(reason, error.reason) }
    }

    @Test fun reorderingUsesTheOriginalRowAndOnlyChangesStart() {
        val raw = json(second, first)
        raw.getJSONObject(0).put("extra", "keep")
        val changed = editReadLogDate(raw, source(), ReadLogDateField.START, "2026-01-03")
        assertEquals("2026-01-03", changed.getJSONObject(1).getString("start"))
        assertEquals(first.finish, changed.getJSONObject(1).getString("finish"))
        assertEquals(raw.getJSONObject(0).toString(), changed.getJSONObject(0).toString())
        assertEquals(first.start, raw.getJSONObject(1).getString("start"))
    }

    @Test fun finishEditPreservesStartAndTheNumberOfCompletedRoundsEvenWhenDatesCollide() {
        val other = first.copy(finish = "2026-01-21")
        val changed = editReadLogDate(json(first, other), source(first, listOf(first, other)), ReadLogDateField.FINISH, other.finish)
        assertEquals(first.start, changed.getJSONObject(0).getString("start"))
        assertEquals(2, MyBooksReadStatus.parseLogs(changed).count { it.finish.isNotEmpty() })
    }

    @Test fun identicalRowsHaveNoEditableIdentityButSharedDatesWithDifferentOtherEndpointsDo() {
        assertNull(readLogDateSource(first, listOf(first, first)))
        assertNotNull(readLogDateSource(first, listOf(first, first.copy(finish = "2026-01-21"))))
        rejects(ReadLogDateFailure.CHANGED) {
            editReadLogDate(json(first, first), source(), ReadLogDateField.START, "2026-01-02")
        }
    }

    @Test fun additionsRemovalAndConcurrentChangesRejectStaleSnapshots() {
        for (raw in listOf(json(first), json(first, second, second.copy(start = "2026-02-02")),
            json(first.copy(finish = "2026-01-21"), second), json(first, second.copy(start = "2026-02-02")))) {
            rejects(ReadLogDateFailure.CHANGED) { editReadLogDate(raw, source(), ReadLogDateField.START, "2026-01-02") }
        }
    }

    @Test fun invalidDatesAndBothOrderingDirectionsAreRejected() {
        for (date in listOf("2026-02-29", "2026-1-01", "0000-01-01", "bad")) {
            rejects(ReadLogDateFailure.INVALID) { editReadLogDate(json(first, second), source(), ReadLogDateField.START, date) }
        }
        rejects(ReadLogDateFailure.ORDER) { editReadLogDate(json(first, second), source(), ReadLogDateField.START, "2026-01-21") }
        rejects(ReadLogDateFailure.ORDER) { editReadLogDate(json(first, second), source(), ReadLogDateField.FINISH, "2025-12-31") }
    }

    @Test fun leapDayAndPartialLogsWorkWithoutCreatingAnotherEndpoint() {
        val log = MyBooksReadLog("2024-02-28", "")
        val changed = editReadLogDate(json(log), source(log, listOf(log)), ReadLogDateField.START, "2024-02-29")
        assertEquals("", changed.getJSONObject(0).getString("finish"))
        assertEquals(0, MyBooksReadStatus.parseLogs(changed).count { it.finish.isNotEmpty() })
        rejects(ReadLogDateFailure.CHANGED) { editReadLogDate(json(log), source(log, listOf(log)), ReadLogDateField.FINISH, "2024-02-29") }
    }
}
