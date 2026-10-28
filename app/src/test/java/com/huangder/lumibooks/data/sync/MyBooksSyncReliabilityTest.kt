package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import com.huangder.lumibooks.data.local.entity.MyBooksPendingDeleteEntity
import com.huangder.lumibooks.data.local.entity.TagEntity
import com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity
import com.huangder.lumibooks.data.repository.BookRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksSyncReliabilityTest {
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var reading: MyBooksReadingSync
    private lateinit var store: MyBooksSyncStateStore
    private lateinit var manager: MyBooksSyncManager
    private lateinit var configState: MutableStateFlow<MyBooksConnection>
    private lateinit var oldConnection: MyBooksConnection
    private lateinit var oldToken: String
    private lateinit var repository: BookRepositoryImpl

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        // Isolate the test account in memory; never read or change encrypted device credentials.
        @Suppress("UNCHECKED_CAST")
        configState = MyBooksConfig::class.java.getDeclaredField("state").apply { isAccessible = true }
            .get(null) as MutableStateFlow<MyBooksConnection>
        oldConnection = configState.value
        val tokenField = MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }
        oldToken = tokenField.get(null) as String
        configState.value = MyBooksConnection(server.url("/").toString().trimEnd('/'), "test", true)
        tokenField.set(null, "test-session")
        repository = BookRepositoryImpl(db.bookDao(), db.syncStateDao(), SyncIdentityStore(db.syncStateDao()))
        reading = MyBooksReadingSync(db, SyncIdentityStore(db.syncStateDao()), context)
        store = MyBooksSyncStateStore(db.syncStateDao(), db.myBooksSyncStateDao(), repository, db.tagDao(), reading)
        manager = MyBooksSyncManager(repository, db.tagDao(), db.syncStateDao(), db.myBooksSyncStateDao(),
            store, MyBooksSyncScheduler(context, store), LocalNetworkAccessManager(context), reading, context)
    }

    @After fun cleanup() {
        configState.value = oldConnection
        MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }.set(null, oldToken)
        server.shutdown()
        db.close()
    }

    private fun remote(title: String = "Remote", revision: Long = 1) = JSONObject().apply {
        put("id", 1); put("uuid", "book-one"); put("revision", revision)
        put("title", title); put("author", "Author"); put("updated_at", "2026-10-28T00:00:00Z")
        put("tags", JSONArray()); put("files", JSONArray())
    }
    private fun snapshot(vararg books: JSONObject) = JSONObject().apply {
        put("sync_version", 2); put("complete", true); put("library_id", "test-library")
        put("books", JSONArray(books.toList())); put("deleted", JSONArray())
    }.toString()
    private suspend fun localBook(id: String = "mybooks-1", title: String = "Remote", readAt: Long = 123L) {
        db.bookDao().insertBook(BookEntity(id, title, "Author", "/local/book.epub", null, "EPUB",
            readAt, .4f, "kept-position", 1L))
    }

    @Test fun discoveringACloudBookDoesNotReplaceTheLastActuallyReadBook() = runBlocking {
        localBook("read-book", "Actually read", 100)
        server.enqueue(MockResponse().setBody(snapshot(remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val imported = db.bookDao().getBookById("mybooks-1")!!
        assertEquals(0L, imported.lastReadTime)
        assertTrue(imported.isCloudOnly)
        assertEquals("read-book", repository.getAllBooks().first().maxBy { it.lastReadTime }.id)
        assertTrue(store.status.first().lastSuccessAt > 0)
    }

    @Test fun failedAttemptsAndReloadedStatusKeepTheLastCompleteSuccessTime() = runBlocking {
        server.enqueue(MockResponse().setBody(snapshot()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val successAt = store.status.first().lastSuccessAt
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"temporarily unavailable\"}"))
        val failed = manager.syncNow(scheduleRetry = false)
        assertFalse(failed.success)
        assertEquals(MyBooksSyncFailure.TRANSIENT, failed.failure)
        val reloaded = MyBooksSyncStateStore(db.syncStateDao(), db.myBooksSyncStateDao(), repository, db.tagDao(), reading).status.first()
        assertEquals(successAt, reloaded.lastSuccessAt)
        assertEquals(MyBooksSyncFailure.TRANSIENT, reloaded.failure)
        assertTrue(reloaded.message.contains("temporarily unavailable"))
        assertEquals(failed.attemptAt, reloaded.lastAttemptAt)
    }

    @Test fun authenticationFailureStopsAutomaticRetriesAndKeepsLocalChanges() = runBlocking {
        localBook(title = "Offline edit")
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString(),
            overridesJson = "{\"title\":\"Offline edit\"}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired session\"}"))
        val result = manager.syncNow(scheduleRetry = false)
        assertEquals(MyBooksSyncFailure.AUTH, result.failure)
        assertEquals(MyBooksWorkerDecision.STOP, myBooksWorkerDecision(result, 0))
        assertEquals(MyBooksSyncFailure.AUTH, store.status.first().failure)
        assertEquals(1, store.pendingCount.first())
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson.contains("Offline edit"))
    }

    @Test fun newBookFailureRemainsVisibleEvenBeforeThereIsALocalBookState() = runBlocking {
        server.enqueue(MockResponse().setBody(snapshot(remote())))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"bad book detail\"}"))
        val result = manager.syncNow(scheduleRetry = false)
        assertFalse(result.success)
        assertEquals(1, result.failedCount)
        assertNull(db.myBooksSyncStateDao().get("mybooks-1"))
        assertTrue(store.status.first().message.contains("Remote"))
        assertTrue(store.status.first().message.contains("bad book detail"))
        assertEquals(0L, store.status.first().lastSuccessAt)
    }

    @Test fun anOfflineEditIsPushedWithItsOriginalRevisionAfterTheConnectionRecovers() = runBlocking {
        localBook(title = "Offline edit")
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1,
            baseJson = remote().toString(), overridesJson = "{\"title\":\"Offline edit\"}", editedAtJson = "{\"title\":2}"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(MyBooksSyncFailure.TRANSIENT, manager.syncNow(scheduleRetry = false).failure)
        assertEquals(1, store.pendingCount.first())
        server.enqueue(MockResponse().setBody(snapshot(remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        server.enqueue(MockResponse().setBody(remote("Offline edit", 2).toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val requests = (0 until 4).map { server.takeRequest() }
        val patch = requests.single { it.method == "PATCH" }
        val body = JSONObject(patch.body.readUtf8())
        assertEquals(1, body.getInt("expected_revision"))
        assertEquals("book-one", body.getString("expected_uuid"))
        assertEquals("Offline edit", body.getString("title"))
        assertEquals("{}", db.myBooksSyncStateDao().get("mybooks-1")!!.overridesJson)
        assertEquals(1, store.pendingCount.first()) // Reading data waits for a protocol-3 server.
        val book = db.bookDao().getBookById("mybooks-1")!!
        assertEquals("/local/book.epub", book.filePath)
        assertEquals("kept-position", book.locatorJson)
        assertEquals(.4f, book.readingProgress, 0f)
    }

    @Test fun pendingCountIncludesTagOnlyChangesAndDeduplicatesQueuedDeletions() = runBlocking {
        localBook()
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity("mybooks-1", 1, baseJson = remote().toString()))
        val tag = TagEntity(id = "tag-one", name = "New tag", normalizedName = "new tag", createdAt = 1, updatedAt = 1)
        db.tagDao().insertTag(tag)
        db.tagDao().insertBookTagLink(BookTagCrossRefEntity("mybooks-1", tag.id))
        assertEquals(1, store.pendingCount.first())
        db.myBooksSyncStateDao().upsertPendingDelete(MyBooksPendingDeleteEntity(1, "mybooks-1", 1))
        assertEquals(1, store.pendingCount.first())
    }

    private fun delta(cursor: String, complete: Boolean, vararg books: JSONObject): String = JSONObject(snapshot(*books))
        .put("sync_version", 3).put("complete", complete).put("cursor", cursor)
        .put("reading_sync", false).put("reading", JSONArray()).toString()

    @Test fun anUnchangedServerDeltaStillPushesTheLocalOutboxAndAdvancesTheCursor() = runBlocking {
        server.enqueue(MockResponse().setBody(delta("cursor-1", true, remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val state = db.myBooksSyncStateDao().get("mybooks-1")!!
        db.bookDao().updateBookTitleAuthor("mybooks-1", "Offline edit", "Author", 2)
        db.myBooksSyncStateDao().upsert(state.copy(overridesJson = JSONObject().put("title", "Offline edit").toString()))
        server.enqueue(MockResponse().setBody(delta("cursor-2", false)))
        server.enqueue(MockResponse().setBody(remote().toString()))
        server.enqueue(MockResponse().setBody(remote("Offline edit", 2).toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val requests = (0 until 5).map { server.takeRequest() }
        assertEquals("/api/sync/changes?cursor=cursor-1", requests[2].path)
        assertEquals("PATCH", requests.last().method)
        assertEquals("cursor-2", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
    }

    @Test fun aDeltaDeletionDoesNotFetchOrUploadPendingEditsForTheDeletedBook() = runBlocking {
        server.enqueue(MockResponse().setBody(delta("cursor-1", true, remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        val state = db.myBooksSyncStateDao().get("mybooks-1")!!
        db.bookDao().updateBookTitleAuthor("mybooks-1", "Local retained", "Author", 2)
        db.bookDao().applyServerReadingPosition("mybooks-1", .6f, "kept", 100)
        db.myBooksSyncStateDao().upsert(state.copy(overridesJson = JSONObject().put("title", "Local retained").toString()))
        val deleted = JSONObject().put("id", 1).put("uuid", "book-one").put("revision", 2).put("deleted_at", "2026-10-28T00:00:00Z")
        server.enqueue(MockResponse().setBody(JSONObject(delta("cursor-2", false)).put("deleted", JSONArray().put(deleted)).toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertTrue(db.myBooksSyncStateDao().get("mybooks-1")!!.remoteDeleted)
        assertEquals("Local retained", db.bookDao().getBookById("mybooks-1")!!.title)
        assertEquals("kept", db.bookDao().getBookById("mybooks-1")!!.locatorJson)
        assertEquals(3, server.requestCount)
    }

    @Test fun failedDeltaItemsDoNotAdvanceTheCursorPastUndeliveredChanges() = runBlocking {
        server.enqueue(MockResponse().setBody(delta("cursor-1", true)))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        server.enqueue(MockResponse().setBody(delta("cursor-2", false, remote())))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        assertFalse(manager.syncNow(scheduleRetry = false).success)
        assertEquals("cursor-1", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
        server.enqueue(MockResponse().setBody(delta("cursor-2", false, remote())))
        server.enqueue(MockResponse().setBody(remote().toString()))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertEquals("cursor-2", db.syncStateDao().getValue(MyBooksSyncManager.KEY_CURSOR))
    }

    @Test fun deletingALocalBookCleansOnlyItsOwnPausedDownloadArtifacts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directory = com.huangder.lumibooks.util.FileUtils.getBooksDirectory(context)
        val partial = java.io.File(directory, "mybooks-1.epub.part").apply { writeText("partial") }
        val metadata = java.io.File(directory, "mybooks-1.epub.part.resume.json").apply { writeText("metadata") }
        val other = java.io.File(directory, "mybooks-2.epub.part").apply { writeText("other") }
        manager.clearLocalBookData("mybooks-1")
        assertFalse(partial.exists()); assertFalse(metadata.exists()); assertTrue(other.exists())
        other.delete()
        Unit
    }

    @Test fun foregroundThrottlingResumesPendingEditsAndHandlesAClockMovingBackwards() {
        val recent = MyBooksSyncStatus(lastSuccessAt = 1000)
        assertFalse(shouldScheduleMyBooksSync(recent, 0, 1050, 100))
        assertTrue(shouldScheduleMyBooksSync(recent, 1, 1050, 100))
        assertTrue(shouldScheduleMyBooksSync(recent, 0, 1100, 100))
        assertTrue(shouldScheduleMyBooksSync(recent, 0, 900, 100))
        assertFalse(shouldScheduleMyBooksSync(recent.copy(failure = MyBooksSyncFailure.AUTH), 1, 2000, 100))
    }

    @Test fun anOldWorkerCannotMarkANewerSuccessfulSyncAsExhausted() = runBlocking {
        store.started(10)
        store.completed(MyBooksSyncResult("offline", false, failure = MyBooksSyncFailure.TRANSIENT, attemptAt = 10))
        store.started(20)
        store.completed(MyBooksSyncResult("complete", true, attemptAt = 20))
        store.retryExhausted(10)
        assertFalse(store.status.first().retryStopped)
        assertNull(store.status.first().failure)
        assertTrue(store.status.first().lastSuccessAt > 0L)
    }

    @Test fun foregroundRequestsAreCoalescedButANewerEditKeepsOneSuccessor() {
        val running = androidx.work.WorkInfo.State.RUNNING
        val queued = androidx.work.WorkInfo.State.ENQUEUED
        val blocked = androidx.work.WorkInfo.State.BLOCKED
        assertTrue(canEnqueueMyBooksSync(emptyList(), false))
        assertFalse(canEnqueueMyBooksSync(listOf(running), false))
        assertTrue(canEnqueueMyBooksSync(listOf(running), true))
        assertFalse(canEnqueueMyBooksSync(listOf(running, blocked), true))
        assertFalse(canEnqueueMyBooksSync(listOf(queued), true))
    }

    @Test fun retryBudgetAndPermanentFailuresAreHandledSeparately() {
        val transient = MyBooksSyncResult("temporary", false, failure = MyBooksSyncFailure.TRANSIENT)
        assertEquals(MyBooksWorkerDecision.RETRY, myBooksWorkerDecision(transient, 3))
        assertEquals(MyBooksWorkerDecision.STOP, myBooksWorkerDecision(transient, 4))
        for (failure in listOf(MyBooksSyncFailure.AUTH, MyBooksSyncFailure.CONFLICT,
            MyBooksSyncFailure.PERMISSION, MyBooksSyncFailure.OTHER)) {
            assertEquals(MyBooksWorkerDecision.STOP,
                myBooksWorkerDecision(MyBooksSyncResult("blocked", false, failure = failure), 0))
        }
        assertEquals(MyBooksSyncFailure.TRANSIENT, classifyMyBooksSyncFailure(IOException("offline")))
        assertEquals(MyBooksSyncFailure.OTHER, classifyMyBooksSyncFailure(SSLHandshakeException("certificate")))
        assertEquals(MyBooksSyncFailure.CONFLICT, classifyMyBooksSyncFailure(MyBooksHttpException(409, "{}")))
    }
}
