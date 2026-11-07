package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MyBooksIncrementalProtocolTest {
    private fun snapshot() = JSONObject().put("sync_version", 3).put("complete", false).put("library_id", "library")
        .put("cursor", "cursor-next").put("books", JSONArray()).put("deleted", JSONArray())
        .put("reading_sync", true).put("reading", JSONArray())
    @Test fun anEmptyIncrementalViewIsAcceptedWithoutBecomingACompleteLibrary() {
        val result = parseMyBooksChanges(snapshot().toString(), "https://books.test")
        assertFalse(result.isFull); assertEquals("cursor-next", result.cursor); assertTrue(result.supportsReadingSync)
        assertTrue(result.books.isEmpty()); assertTrue(result.deleted.isEmpty())
    }
    @Test fun invalidCompletenessAndDuplicateReadingHeadsAreRejected() {
        val head = JSONObject().put("id", 1).put("uuid", "one").put("revision", 0)
        val invalid = listOf(snapshot().put("complete", "false"), snapshot().put("cursor", ""),
            snapshot().put("reading", JSONArray().put(head).put(head)))
        for (value in invalid) {
            try { parseMyBooksChanges(value.toString(), "https://books.test"); fail("invalid snapshot") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }
}
