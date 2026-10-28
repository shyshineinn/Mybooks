package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksReadingSyncTest {
    private data class Device(val db: AppDatabase, val sync: MyBooksReadingSync)
    private val devices = mutableListOf<Device>()
    private lateinit var server: MockWebServer
    private lateinit var config: MutableStateFlow<MyBooksConnection>
    private lateinit var oldConfig: MyBooksConnection
    private lateinit var oldToken: String
    @Volatile private var remote = JSONObject()
    private var beforePut: (() -> Unit)? = null
    private var headerDelayMs: Long = 0
    private fun root(revision: Long, data: JSONObject) = JSONObject().put("book_id", 1).put("book_uuid", "book-one")
        .put("revision", revision).put("data", data)

    @Before fun setup() {
        remote = root(0, emptyMyBooksReading())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/api/sync/reading/1") return MockResponse().setResponseCode(404)
                if (request.method == "GET") return MockResponse().setBody(remote.toString()).setHeadersDelay(headerDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                val body = JSONObject(request.body.readUtf8())
                val callback = beforePut; beforePut = null; callback?.invoke()
                if (body.getLong("expected_revision") != remote.getLong("revision")) {
                    return MockResponse().setResponseCode(409).setBody(JSONObject().put("current", remote).toString())
                }
                remote = root(remote.getLong("revision") + 1, body.getJSONObject("data"))
                return MockResponse().setBody(remote.toString())
            }
        }
        server.start()
        @Suppress("UNCHECKED_CAST")
        config = MyBooksConfig::class.java.getDeclaredField("state").apply { isAccessible = true }.get(null) as MutableStateFlow<MyBooksConnection>
        oldConfig = config.value
        val token = MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }
        oldToken = token.get(null) as String; token.set(null, "test-session")
        config.value = MyBooksConnection(server.url("/").toString().trimEnd('/'), "test", true)
    }
    @After fun cleanup() {
        config.value = oldConfig
        MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }.set(null, oldToken)
        devices.forEach { it.db.close() }; server.shutdown()
    }
    private suspend fun device(): Device {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.bookDao().insertBook(BookEntity("mybooks-1", "Book", "Author", "", null, "EPUB", 0, 0f, createdAt = 1, isCloudOnly = true))
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1,
            baseJson = JSONObject().put("id", 1).put("uuid", "book-one").put("revision", 1).put("title", "Book").put("author", "Author").toString()))
        return Device(db, MyBooksReadingSync(db, SyncIdentityStore(db.syncStateDao()), context)).also { devices += it }
    }
    private fun snapshot() = MyBooksSyncSnapshot(emptyList(), libraryId = "library-one", syncVersion = 3,
        supportsReadingSync = true, reading = listOf(MyBooksReadingHead(1, "book-one", remote.getLong("revision"))))
    private suspend fun sync(device: Device) { assertTrue(device.sync.sync(snapshot()).isEmpty()) }
    private fun note(id: String, value: String) = NoteEntity(bookId = "mybooks-1", chapterIndex = 0,
        startPosition = 1, endPosition = 2, selectedText = "selected", note = value, color = "#ffff00",
        createdAt = 1, syncId = id, updatedAt = 2)
    private fun bookmark() = BookmarkEntity(bookId = "mybooks-1", chapterIndex = 0, position = .3f,
        title = "Bookmark", createdAt = 1, syncId = "mark-one", updatedAt = 2)

    @Test fun twoDevicesExchangePositionsAnnotationsAndStatisticsWithoutDuplicates() = runBlocking {
        val a = device(); val b = device()
        a.db.bookDao().applyServerReadingPosition("mybooks-1", .4f, "saved-position", 100)
        a.db.syncStateDao().upsertBookmarks(listOf(bookmark()))
        a.db.syncStateDao().upsertNotes(listOf(note("a", "A note")))
        a.db.syncStateDao().upsertReadingRecords(listOf(ReadingRecordEntity(bookId = "mybooks-1", date = "2026-10-28",
            duration = 50, startTime = 1, endTime = 51, sourceDeviceId = "device-a")))
        sync(a); sync(b)
        assertEquals(.4f, b.db.bookDao().getBookById("mybooks-1")!!.readingProgress, 0f)
        assertEquals("saved-position", b.db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertEquals(1, b.db.syncStateDao().getAllBookmarks().size)
        b.db.syncStateDao().upsertNotes(listOf(note("b", "B note")))
        b.db.syncStateDao().upsertReadingRecords(listOf(ReadingRecordEntity(bookId = "mybooks-1", date = "2026-10-28",
            duration = 30, startTime = 1, endTime = 31, sourceDeviceId = "device-b")))
        sync(b); sync(a); sync(a); sync(b)
        assertEquals(setOf("a", "b"), a.db.syncStateDao().getAllNotes().map { it.syncId }.toSet())
        assertEquals(80L, a.db.syncStateDao().getAllReadingRecords().sumOf { it.duration })
        assertEquals(80L, b.db.syncStateDao().getAllReadingRecords().sumOf { it.duration })
        assertTrue(a.sync.pendingFingerprints.first().isEmpty())
        assertTrue(b.sync.pendingFingerprints.first().isEmpty())
    }
    @Test fun explicitAnnotationDeletionPropagatesAndDoesNotResurrect() = runBlocking {
        val a = device(); val b = device()
        a.db.syncStateDao().upsertBookmarks(listOf(bookmark())); sync(a); sync(b)
        a.db.syncStateDao().upsertTombstones(listOf(SyncTombstoneEntity("bookmark", "mark-one", 200, "device-a")))
        a.db.syncStateDao().deleteBookmarksBySyncIds(listOf("mark-one"))
        sync(a); sync(b); sync(a)
        assertTrue(b.db.syncStateDao().getAllBookmarks().isEmpty())
        assertEquals(1, b.db.syncStateDao().getAllTombstones().size)
        assertEquals(1, readingItems(remote.getJSONObject("data"), "tombstones").size)
    }
    @Test fun concurrentAnnotationChangesStayLocalUntilTheUserChooses() = runBlocking {
        val a = device(); val b = device()
        a.db.syncStateDao().upsertNotes(listOf(note("n", "old"))); sync(a); sync(b)
        val an = a.db.syncStateDao().getAllNotes().single()
        val bn = b.db.syncStateDao().getAllNotes().single()
        a.db.syncStateDao().upsertNotes(listOf(an.copy(note = "server A", updatedAt = 999999)))
        b.db.syncStateDao().upsertNotes(listOf(bn.copy(note = "local B", updatedAt = 3)))
        sync(a)
        val errors = b.sync.sync(snapshot())
        assertTrue(errors.single().second is MyBooksReadingConflict)
        assertEquals("local B", b.db.syncStateDao().getAllNotes().single().note)
        assertTrue(b.sync.issues.first().single().readingConflict)
        b.sync.choose("mybooks-1", false); sync(b)
        assertEquals("server A", b.db.syncStateDao().getAllNotes().single().note)
        assertTrue(b.sync.issues.first().isEmpty())
    }
    @Test fun editsMadeWhileAnUploadIsInFlightAreNotAcknowledgedOrOverwritten() = runBlocking {
        val a = device()
        a.db.syncStateDao().upsertNotes(listOf(note("n", "sent")))
        beforePut = { runBlocking {
            val old = a.db.syncStateDao().getAllNotes().single()
            a.db.syncStateDao().upsertNotes(listOf(old.copy(note = "new edit", updatedAt = 3)))
        } }
        sync(a)
        assertEquals("new edit", a.db.syncStateDao().getAllNotes().single().note)
        assertEquals("sent", readingItems(remote.getJSONObject("data"), "notes").single().getString("note"))
        assertTrue(a.sync.pendingFingerprints.first().containsKey("mybooks-1"))
        sync(a)
        assertEquals("new edit", readingItems(remote.getJSONObject("data"), "notes").single().getString("note"))
        assertTrue(a.sync.pendingFingerprints.first().isEmpty())
    }
    @Test fun readingPositionsOnlyApplyToTheMatchingBody() = runBlocking {
        val b = device()
        b.db.bookDao().updateSourceSha256("mybooks-1", "b".repeat(64))
        val p = JSONObject().put("format", "EPUB").put("fileSha256", "a".repeat(64)).put("lastReadTime", 100)
            .put("readingProgress", .7).put("locatorJson", "remote-position").put("sourceDeviceId", "a")
        remote = root(1, emptyMyBooksReading().put("positions", JSONArray().put(p)))
        sync(b)
        assertEquals(0f, b.db.bookDao().getBookById("mybooks-1")!!.readingProgress, 0f)
        b.sync.markDownloaded("mybooks-1", "/local/book.epub", "EPUB", "a".repeat(64))
        b.sync.applyKnownProgress("mybooks-1")
        assertEquals(.7f, b.db.bookDao().getBookById("mybooks-1")!!.readingProgress, 0f)
        assertEquals("remote-position", b.db.bookDao().getBookById("mybooks-1")!!.locatorJson)
    }
    @Test fun aCasConflictReReadsAndMergesIndependentRemoteChanges() = runBlocking {
        val a = device()
        a.db.syncStateDao().upsertNotes(listOf(note("local", "Local")))
        beforePut = {
            val other = JSONObject().put("syncId", "remote").put("chapterIndex", 0).put("startPosition", 1)
                .put("endPosition", 2).put("selectedText", "x").put("note", "Remote").put("color", "yellow")
                .put("createdAt", 1).put("updatedAt", 1)
            remote = root(1, emptyMyBooksReading().put("notes", JSONArray().put(other)))
        }
        sync(a)
        assertEquals(setOf("local", "remote"), a.db.syncStateDao().getAllNotes().map { it.syncId }.toSet())
        assertEquals(2L, remote.getLong("revision"))
    }
    @Test fun openingABookFetchesItsLatestPositionWithoutAFullBookSync() = runBlocking {
        val b = device()
        val p = JSONObject().put("format", "EPUB").put("fileSha256", "").put("lastReadTime", 200)
            .put("readingProgress", .6).put("locatorJson", "latest-position").put("sourceDeviceId", "a")
        remote = root(1, emptyMyBooksReading().put("positions", JSONArray().put(p)))
        b.sync.beforeOpen("mybooks-1", "library-one")
        assertEquals("latest-position", b.db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertEquals(1, server.requestCount)
    }
    @Test fun aSlowReadingEndpointTimesOutWithoutChangingTheLocalPosition() = runBlocking {
        val b = device(); headerDelayMs = 1000
        try { b.sync.beforeOpen("mybooks-1", "library-one", timeoutMs = 50); fail("must time out") }
        catch (_: java.io.IOException) { }
        assertEquals(0f, b.db.bookDao().getBookById("mybooks-1")!!.readingProgress, 0f)
    }

    @Test fun annotationsForAnotherBodyRemainReadableButCannotHighlightOrJumpUntilTheBodyMatches() = runBlocking {
        val a = device(); val b = device()
        a.db.bookDao().updateSourceSha256("mybooks-1", "a".repeat(64))
        b.db.bookDao().updateSourceSha256("mybooks-1", "b".repeat(64))
        a.db.syncStateDao().upsertNotes(listOf(note("n", "retained quote")))
        sync(a); sync(b)
        assertEquals("retained quote", b.db.syncStateDao().getAllNotes().single().note)
        assertTrue(b.db.syncStateDao().getAllNotes().single().isStale)
        assertTrue(b.sync.pendingFingerprints.first().isEmpty())
        b.sync.markDownloaded("mybooks-1", "/new/a.epub", "EPUB", "a".repeat(64))
        b.sync.applyKnownProgress("mybooks-1")
        assertFalse(b.db.syncStateDao().getAllNotes().single().isStale)
        assertTrue(b.sync.pendingFingerprints.first().isEmpty())
    }
    @Test fun bodyReplacementPreservesUnsyncedOldPositionsAndAppliesOnlyTheNewBodyPosition() = runBlocking {
        val b = device()
        b.db.bookDao().updateSourceSha256("mybooks-1", "b".repeat(64))
        val p = JSONObject().put("format", "EPUB").put("fileSha256", "a".repeat(64)).put("lastReadTime", 100)
            .put("readingProgress", .4).put("locatorJson", "new body position").put("sourceDeviceId", "a")
        remote = root(1, emptyMyBooksReading().put("positions", JSONArray().put(p)))
        sync(b)
        b.db.bookDao().applyServerReadingPosition("mybooks-1", .8f, "old body position", 200)
        b.sync.markDownloaded("mybooks-1", "/new/a.epub", "EPUB", "a".repeat(64))
        b.sync.applyKnownProgress("mybooks-1")
        assertEquals("new body position", b.db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        sync(b)
        val positions = readingItems(remote.getJSONObject("data"), "positions").associateBy { it.getString("fileSha256") }
        assertEquals("old body position", positions.getValue("b".repeat(64)).getString("locatorJson"))
        assertEquals("new body position", positions.getValue("a".repeat(64)).getString("locatorJson"))
        assertTrue(b.sync.pendingFingerprints.first().isEmpty())
    }
    @Test fun completingADownloadOfTheSameBodyDoesNotOverwriteNewLocalReadingChanges() = runBlocking {
        val b = device()
        b.db.bookDao().updateSourceSha256("mybooks-1", "a".repeat(64))
        val p = JSONObject().put("format", "EPUB").put("fileSha256", "a".repeat(64)).put("lastReadTime", 100)
            .put("readingProgress", .4).put("locatorJson", "old server position").put("sourceDeviceId", "a")
        remote = root(1, emptyMyBooksReading().put("positions", JSONArray().put(p)))
        sync(b)
        b.db.bookDao().applyServerReadingPosition("mybooks-1", .9f, "new local position", 300)
        b.sync.markDownloaded("mybooks-1", "/same/a.epub", "EPUB", "a".repeat(64))
        b.sync.applyKnownProgress("mybooks-1")
        assertEquals("new local position", b.db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertTrue(b.sync.pendingFingerprints.first().containsKey("mybooks-1"))
        sync(b)
        assertEquals("new local position", readingItems(remote.getJSONObject("data"), "positions").single().getString("locatorJson"))
    }

    @Test fun oldOrMalformedBaselinesCannotCrashTheReadingChangeObserver() = runBlocking {
        val a = device()
        val state = a.db.myBooksSyncStateDao().get("mybooks-1")!!
        a.db.myBooksSyncStateDao().upsert(state.copy(baseJson = ""))
        assertTrue(a.sync.pendingFingerprints.first().isEmpty())
        a.db.myBooksSyncStateDao().upsert(state)
        a.db.syncStateDao().upsertNotes(listOf(note("n", "retained")))
        a.db.syncStateDao().putState(SyncStateEntity(MyBooksReadingSync.BASE + "book-one", "invalid json"))
        assertTrue(a.sync.pendingFingerprints.first().containsKey("mybooks-1"))
        assertEquals(1, a.sync.sync(snapshot()).size)
        assertEquals("retained", a.db.syncStateDao().getAllNotes().single().note)
    }

    @Test fun serverDeletionRetainsLocalReadingAssetsAndStopsReadingUploads() = runBlocking {
        val a = device()
        a.db.syncStateDao().upsertNotes(listOf(note("n", "retained")))
        val state = a.db.myBooksSyncStateDao().get("mybooks-1")!!
        a.db.myBooksSyncStateDao().upsert(state.copy(remoteDeleted = true))
        sync(a)
        assertEquals("retained", a.db.syncStateDao().getAllNotes().single().note)
        assertEquals(0, server.requestCount)
    }

    @Test fun downloadingReadingDataOnlyReadsTheServerAndKeepsLocalEditsPendingForLaterUpload() = runBlocking {
        val a = device()
        a.db.syncStateDao().upsertNotes(listOf(note("local", "Local note")))
        val serverNote = JSONObject().put("syncId", "remote").put("chapterIndex", 0).put("startPosition", 1)
            .put("endPosition", 2).put("selectedText", "quote").put("note", "Remote note").put("color", "yellow")
            .put("createdAt", 1).put("updatedAt", 1)
        remote = root(1, emptyMyBooksReading().put("notes", JSONArray().put(serverNote)))
        assertTrue(a.sync.sync(snapshot(), allowUpload = false).isEmpty())
        assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
        assertEquals(1L, remote.getLong("revision"))
        assertEquals(setOf("local", "remote"), a.db.syncStateDao().getAllNotes().map { it.syncId }.toSet())
        assertTrue(a.sync.pendingFingerprints.first().containsKey("mybooks-1"))
        sync(a)
        assertEquals(setOf("local", "remote"), readingItems(remote.getJSONObject("data"), "notes").map { it.getString("syncId") }.toSet())
        assertTrue(a.sync.pendingFingerprints.first().isEmpty())
    }

}
