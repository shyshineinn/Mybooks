package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.backup.PortableSnapshotManager
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.TagEntity
import com.huangder.lumibooks.data.repository.BookRepositoryImpl
import com.huangder.lumibooks.mineru.MineruTokenStore
import com.huangder.lumibooks.translation.TranslationTokenStore
import com.huangder.lumibooks.tts.ExternalTtsTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Exercise book metadata followed by the real Room organization apply, then another full sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksTagSyncRegressionTest {
    private lateinit var database: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var manager: MyBooksSyncManager
    private lateinit var snapshots: PortableSnapshotManager
    private lateinit var connection: MutableStateFlow<MyBooksConnection>
    private lateinit var oldConnection: MyBooksConnection
    private lateinit var oldToken: String
    private var book = JSONObject()
    private var portable: JSONObject? = null
    private var portableRevision = 0L
    private var patches = 0

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        @Suppress("UNCHECKED_CAST")
        connection = MyBooksConfig::class.java.getDeclaredField("state").apply { isAccessible = true }.get(null) as MutableStateFlow<MyBooksConnection>
        oldConnection = connection.value
        val tokenField = MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }
        oldToken = tokenField.get(null) as String
        connection.value = MyBooksConnection(server.url("/").toString().trimEnd('/'), "test", true)
        tokenField.set(null, "test-session")
        val identity = SyncIdentityStore(database.syncStateDao())
        snapshots = PortableSnapshotManager(context, database, DataStoreManager(context), identity,
            MineruTokenStore(context), ExternalTtsTokenStore(context), TranslationTokenStore(context))
        val repository = BookRepositoryImpl(database.bookDao(), database.syncStateDao(), identity)
        val reading = MyBooksReadingSync(database, identity, context)
        val store = MyBooksSyncStateStore(database.syncStateDao(), database.myBooksSyncStateDao(), repository, database.tagDao(), reading)
        val preferences = MyBooksSyncPreferencesStore(database.syncStateDao())
        manager = MyBooksSyncManager(repository, database.tagDao(), database.syncStateDao(), database.myBooksSyncStateDao(),
            store, MyBooksSyncScheduler(context, store, preferences), LocalNetworkAccessManager(context), reading, context,
            preferences, javax.inject.Provider { MyBooksPortableStateSync(context, database, snapshots) },
            javax.inject.Provider { MyBooksBookTransfer(database, manager, context) }, database)
        book = JSONObject().put("id", 1).put("uuid", "book-uuid").put("revision", 1)
            .put("title", "Book").put("author", "Author").put("tags", JSONArray(listOf("Old")))
            .put("files", JSONArray()).put("updated_at", "2026-11-06T00:00:00Z")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer test-session", request.getHeader("Authorization"))
                val path = request.path!!.substringBefore('?')
                val result = when {
                    path == "/api/sync/changes" -> JSONObject().put("sync_version", 3).put("complete", true)
                        .put("library_id", "library").put("cursor", "cursor-${book.getLong("revision")}")
                        .put("portable_sync", true).put("portable_revision", portableRevision).put("reading_sync", false)
                        .put("books", JSONArray().put(book)).put("deleted", JSONArray()).put("reading", JSONArray())
                    path == "/api/books/1" -> {
                        if (request.method == "PATCH") {
                            val patch = JSONObject(request.body.readUtf8())
                            assertEquals(book.getLong("revision"), patch.getLong("expected_revision"))
                            assertEquals("book-uuid", patch.getString("expected_uuid"))
                            patches++
                            if (patch.has("tags")) book.put("tags", patch.getJSONArray("tags"))
                            book.put("revision", book.getLong("revision") + 1)
                        }
                        book
                    }
                    path == "/api/sync/state" -> {
                        if (request.method == "PUT") {
                            val input = JSONObject(request.body.readUtf8())
                            assertEquals(portableRevision, input.getLong("expected_revision"))
                            portable = input.getJSONObject("data"); portableRevision++
                        }
                        JSONObject().put("library_id", "library").put("revision", portableRevision).put("data", portable ?: JSONObject.NULL)
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(result.toString())
            }
        }
    }

    @After fun cleanup() {
        connection.value = oldConnection
        MyBooksConfig::class.java.getDeclaredField("deviceToken").apply { isAccessible = true }.set(null, oldToken)
        server.shutdown(); database.close()
    }

    private suspend fun webEditThenSync(tags: List<String>) {
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        // A historical organization snapshot still refers to the old tag after the Web edits the book.
        portable = JSONObject(snapshots.capture(includeBookFiles = false).snapshot.toJson())
            .put("bookIdentities", JSONObject().put("mybooks-1", "book-uuid"))
        portableRevision++
        book.put("tags", JSONArray(tags)).put("revision", book.getLong("revision") + 1)
        repeat(3) {
            val result = manager.syncNow(scheduleRetry = false)
            assertTrue(result.toString(), result.success)
            assertEquals(tags.toSet(), database.tagDao().getTagsForBook("mybooks-1").map { it.name }.toSet())
            assertEquals(tags.toSet(), (0 until book.getJSONArray("tags").length()).map { book.getJSONArray("tags").getString(it) }.toSet())
            assertEquals("remote-only edits never cause a tag PATCH", 0, patches)
        }
    }

    @Test fun webReplacementSurvivesRepeatedFullSyncs() = runBlocking { webEditThenSync(listOf("Web")) }
    @Test fun webClearingAllTagsSurvivesRepeatedFullSyncs() = runBlocking { webEditThenSync(emptyList()) }
    @Test fun genuineAppTagChangesStillUploadAfterAdoptingWebTags() = runBlocking {
        webEditThenSync(listOf("Web"))
        database.tagDao().createAndAssignTag("mybooks-1", TagEntity("app-tag", "App", "app", 1))
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertEquals(setOf("Web", "App"), (0 until book.getJSONArray("tags").length()).map { book.getJSONArray("tags").getString(it) }.toSet())
        assertEquals(1, patches)
        assertTrue(manager.syncNow(scheduleRetry = false).success)
        assertEquals(1, patches)
    }
}
