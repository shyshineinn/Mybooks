package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MyBooksSyncSafetyTest {
    private fun snapshot() = JSONObject("""{"sync_version":2,"complete":true,"library_id":"library-one","books":[],"deleted":[{"id":1,"uuid":"book-one","revision":4,"deleted_at":"2026-10-28T00:00:00Z"}]}""")
    @Test fun validatesExplicitDeletesAndAcceptsACompletelyEmptyLibrary() {
        val result = parseMyBooksSyncSnapshot(snapshot().toString(), "https://books.test")
        assertEquals("book-one", result.deleted.single().uuid)
        val empty = snapshot().put("deleted", org.json.JSONArray())
        assertTrue(parseMyBooksSyncSnapshot(empty.toString(), "https://books.test").deleted.isEmpty())
        assertFalse(matchesMyBooksDeletion("""{"uuid":"different-book","revision":3}""", result.deleted.single()))
        assertFalse(matchesMyBooksDeletion("""{"uuid":"book-one","revision":5}""", result.deleted.single()))
        assertTrue(matchesMyBooksDeletion("""{"uuid":"book-one","revision":3}""", result.deleted.single()))
    }
    @Test fun rejectsIncompleteDuplicateAndMalformedResponses() {
        val duplicate = snapshot().put("books", org.json.JSONArray("""[{"id":1,"uuid":"book-two","revision":1,"title":"Book","deleted_at":null}]"""))
        val missingUuid = snapshot().also { it.getJSONArray("deleted").getJSONObject(0).remove("uuid") }
        val fractional = snapshot().also { it.getJSONArray("deleted").getJSONObject(0).put("id", 1.5) }
        for (invalid in listOf(snapshot().put("complete", false), snapshot().put("complete", "true"),
            duplicate, missingUuid, fractional, snapshot().put("books", "not-an-array"))) {
            try { parseMyBooksSyncSnapshot(invalid.toString(), "https://books.test"); fail("Accepted malformed snapshot") }
            catch (_: Exception) { }
        }
    }
    @Test fun aResponseCannotAcknowledgeANewerLocalEditEvenIfTheClockDidNotAdvance() {
        val sent = MyBooksSyncStateEntity("mybooks-1", 1,
            overridesJson = """{"title":"sent","author":"sent-author"}""",
            editedAtJson = """{"title":10,"author":10}""")
        val latest = sent.copy(overridesJson = """{"title":"new-edit","author":"sent-author","summary":"new"}""")
        assertEquals(setOf("author"), acknowledgedMyBooksKeys(sent, latest, setOf("title", "author")))
        val laterSameValue = sent.copy(editedAtJson = """{"title":11,"author":10}""")
        assertFalse(acknowledgedMyBooksKeys(sent, laterSameValue, setOf("title")).contains("title"))
    }
    @Test fun oldServersFallbackToMetadataWithoutDeletionAuthority() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("[]"))
            val client = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') })
            val result = client.fetchSyncSnapshot()
            assertEquals(1, result.syncVersion)
            assertTrue(result.deleted.isEmpty())
            assertEquals("/api/sync/changes", server.takeRequest().path)
            assertEquals("/api/sync/books", server.takeRequest().path)
            assertEquals("/api/books", server.takeRequest().path)
        } finally { server.shutdown() }
    }
    @Test fun conditionalDeletesSendTheOriginalIdentityAndRevision() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            val client = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') })
            client.deleteBook(1, "device-token", JSONObject().put("expected_revision", 3).put("expected_uuid", "book-one"))
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(3, body.getInt("expected_revision"))
            assertEquals("book-one", body.getString("expected_uuid"))
            assertEquals("Bearer device-token", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
