package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.backup.PortableSyncRules
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Explicit opt-in, local disposable server only; never embeds credentials in source or reports. */
class PoxenLiveConnectionTest {
    @Test fun catalogCursorTracksMetadataAndAccountStateAgainstDisposableDocker() = runBlocking {
        val path = System.getenv("MYBOOKS_TEST_WRITE_CREDENTIALS")
        assumeTrue("Set MYBOOKS_TEST_WRITE_CREDENTIALS to a disposable test server", !path.isNullOrBlank())
        val config = JSONObject(File(requireNotNull(path)).readText())
        val url = config.getString("url")
        require(url == "http://127.0.0.1:3081") { "Write test requires the disposable Docker endpoint" }
        val id = config.getLong("fixture_book_id")
        val auth = MyBooksAuthClient(url)
        val token = auth.login(config.getString("username"), config.getString("password"))
        val api = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
        try {
            val initial = JSONObject(api.fetchBookJson(id))
            val full = api.fetchSyncSnapshot()
            assertTrue(full.isFull)
            assertTrue(full.books.any { it.id == id })
            val cursor = requireNotNull(full.cursor)
            val unchanged = api.fetchSyncSnapshot(cursor)
            assertFalse(unchanged.isFull)
            assertEquals(cursor, unchanged.cursor)
            assertTrue(unchanged.books.isEmpty())
            try {
                val metadata = JSONObject(api.patchBook(id, JSONObject()
                    .put("title", initial.getString("title") + " cursor fixture")
                    .put("tags", JSONArray().put("cursor fixture")), initial, token))
                val updated = api.fetchSyncSnapshot(cursor)
                assertFalse(updated.isFull)
                assertEquals(listOf(id), updated.books.map { it.id })
                assertEquals(metadata.getString("title"), updated.books.single().title)

                api.patchBook(id, JSONObject()
                    .put("favorite", !initial.getBoolean("favorite"))
                    .put("status", if (initial.getString("status") == "reading") "done" else "reading"),
                    metadata, token)
                val accountChange = api.fetchSyncSnapshot(updated.cursor)
                assertFalse(accountChange.isFull)
                assertEquals(listOf(id), accountChange.books.map { it.id })
                assertTrue(api.fetchSyncSnapshot("expired-cursor").isFull)
            } finally {
                val current = JSONObject(api.fetchBookJson(id))
                api.patchBook(id, JSONObject().put("title", initial.getString("title"))
                    .put("tags", initial.getJSONArray("tags"))
                    .put("favorite", initial.getBoolean("favorite"))
                    .put("status", initial.getString("status")), current, token)
            }
        } finally { auth.logout(token) }
    }

    @Test fun portableProfileAndLayoutRoundTripAgainstDisposableDocker() = runBlocking {
        val path = System.getenv("MYBOOKS_TEST_WRITE_CREDENTIALS")
        assumeTrue("Set MYBOOKS_TEST_WRITE_CREDENTIALS to a disposable test server", !path.isNullOrBlank())
        val config = JSONObject(File(requireNotNull(path)).readText())
        val url = config.getString("url")
        require(url == "http://127.0.0.1:3081") { "Write test requires the disposable Docker endpoint" }
        val id = config.getLong("fixture_book_id")
        val auth = MyBooksAuthClient(url)
        val token = auth.login(config.getString("username"), config.getString("password"))
        val api = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
        val asset = File.createTempFile("mybooks-profile-", ".jpg")
        val downloaded = File.createTempFile("mybooks-profile-download-", ".jpg")
        try {
            val snapshot = api.fetchSyncSnapshot()
            assertTrue(snapshot.supportsPortableSync)
            val library = requireNotNull(snapshot.libraryId)
            val initial = api.fetchPortableState()
            val uuid = JSONObject(api.fetchBookJson(id)).getString("uuid")
            val bookId = "mybooks-$id"
            val bytes = "portable-avatar-fixture".toByteArray()
            asset.writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val name = "$sha.jpg"
            api.uploadPortableAsset(asset, name)
            val empty = JSONObject(PortableSyncRules.empty("fixture-device").toJson()).put("bookIdentities", JSONObject())
            val data = JSONObject(empty.toString())
                .put("bookIdentities", JSONObject().put(bookId, uuid))
                .put("books", JSONArray().put(JSONObject().put("id", bookId).put("isPinned", true)))
                .put("preferences", JSONArray()
                    .put(JSONObject().put("key", "font_size").put("type", "float").put("value", "21"))
                    .put(JSONObject().put("key", "avatar_uri").put("type", "string").put("value", "asset://$sha")))
                .put("folders", JSONArray().put(JSONObject().put("id", "fixture-folder")
                    .put("name", "Fixture folder").put("normalizedName", "fixture folder")))
                .put("bookFolderLinks", JSONArray().put(JSONObject().put("bookId", bookId).put("folderId", "fixture-folder")))
                .put("assets", JSONArray().put(JSONObject().put("id", sha).put("sha256", sha)
                    .put("fileName", name).put("sizeBytes", bytes.size).put("kind", "avatar").put("ownerId", "profile")))
            val saved = api.putPortableState(library, initial.getLong("revision"), data)
            try {
                val reader = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
                val received = reader.fetchPortableState()
                assertEquals(saved.getLong("revision"), received.getLong("revision"))
                assertEquals(readingCanonical(data), readingCanonical(received.getJSONObject("data")))
                val receipt = reader.downloadPortableAsset(name, downloaded, bytes.size.toLong())
                assertEquals(sha, receipt.sha256)
                assertArrayEquals(bytes, downloaded.readBytes())
            } finally {
                api.putPortableState(library, saved.getLong("revision"), initial.optJSONObject("data") ?: empty)
            }
        } finally {
            asset.delete()
            downloaded.delete()
            auth.logout(token)
        }
    }

    @Test fun readingTimelineRoundTripsAgainstDisposableDocker() = runBlocking {
        val path = System.getenv("MYBOOKS_TEST_WRITE_CREDENTIALS")
        assumeTrue("Set MYBOOKS_TEST_WRITE_CREDENTIALS to a disposable test server", !path.isNullOrBlank())
        val config = JSONObject(File(requireNotNull(path)).readText())
        val url = config.getString("url")
        require(url == "http://127.0.0.1:3081") { "Write test requires the disposable Docker endpoint" }
        val id = config.getLong("fixture_book_id")
        val auth = MyBooksAuthClient(url)
        val token = auth.login(config.getString("username"), config.getString("password"))
        val api = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
        try {
            val initial = JSONObject(api.fetchBookJson(id))
            val originalLogs = initial.getJSONArray("read_logs")
            try {
                val started = listOf(MyBooksReadLog(start = "2026-11-01"))
                val reading = JSONObject(api.patchBook(id, JSONObject().put("status", "reading")
                    .put("read_logs", MyBooksReadStatus.logsToJson(started)), initial, token))
                assertEquals("reading", reading.getString("status"))
                assertEquals(started, MyBooksReadStatus.parseLogs(reading.getJSONArray("read_logs")))

                val completed = listOf(MyBooksReadLog(start = "2026-11-01", finish = "2026-11-03"))
                val done = JSONObject(api.patchBook(id, JSONObject().put("status", "done")
                    .put("read_logs", MyBooksReadStatus.logsToJson(completed)), reading, token))
                assertEquals("done", done.getString("status"))

                val reread = completed + MyBooksReadLog(start = "2026-11-04")
                val readingAgain = JSONObject(api.patchBook(id, JSONObject().put("status", "reading")
                    .put("read_logs", MyBooksReadStatus.logsToJson(reread)), done, token))
                val edited = listOf(completed.single().copy(finish = "2026-11-02"), reread.last())
                api.patchBook(id, JSONObject().put("read_logs", MyBooksReadStatus.logsToJson(edited)), readingAgain, token)

                val reader = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
                val received = JSONObject(reader.fetchBookJson(id))
                assertEquals("reading", received.getString("status"))
                assertEquals(edited, MyBooksReadStatus.parseLogs(received.getJSONArray("read_logs")))
            } finally {
                val current = JSONObject(api.fetchBookJson(id))
                api.patchBook(id, JSONObject().put("status", initial.getString("status"))
                    .put("read_logs", originalLogs), current, token)
            }
        } finally { auth.logout(token) }
    }

    @Test fun readingDataRoundTripsAgainstDisposableDocker() = runBlocking {
        val path = System.getenv("MYBOOKS_TEST_WRITE_CREDENTIALS")
        assumeTrue("Set MYBOOKS_TEST_WRITE_CREDENTIALS to a disposable test server", !path.isNullOrBlank())
        val config = JSONObject(File(requireNotNull(path)).readText())
        val url = config.getString("url")
        require(url == "http://127.0.0.1:3081") { "Write test requires the disposable Docker endpoint" }
        val id = config.getLong("fixture_book_id")
        val auth = MyBooksAuthClient(url)
        val token = auth.login(config.getString("username"), config.getString("password"))
        val api = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
        try {
            val snapshot = api.fetchSyncSnapshot()
            assertTrue(snapshot.supportsReadingSync)
            val library = requireNotNull(snapshot.libraryId)
            val initial = api.fetchReadingState(id)
            val uuid = initial.getString("book_uuid")
            val data = emptyMyBooksReading()
            data.getJSONArray("positions").put(JSONObject()
                .put("format", "EPUB").put("fileSha256", "a".repeat(64))
                .put("readingProgress", 0.42).put("lastReadTime", 123L))
            data.getJSONArray("bookmarks").put(JSONObject()
                .put("syncId", "fixture-bookmark").put("chapterIndex", 1).put("position", 0.5))
            data.getJSONArray("notes")
                .put(JSONObject().put("syncId", "fixture-highlight")
                    .put("selectedText", "Highlighted text").put("isNote", false))
                .put(JSONObject().put("syncId", "fixture-note")
                    .put("selectedText", "Annotated text").put("note", "Reader note").put("isNote", true))
            data.getJSONArray("reading_records").put(JSONObject()
                .put("date", "2026-11-07").put("sourceDeviceId", "fixture-device").put("duration", 120L))
            validateMyBooksReading(data)

            val saved = api.putReadingState(id, library, uuid, initial.getLong("revision"), data)
            val revision = saved.getLong("revision")
            try {
                val reader = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
                val received = reader.fetchReadingState(id)
                assertEquals(initial.getLong("revision") + 1, revision)
                assertEquals(revision, received.getLong("revision"))
                assertEquals(readingCanonical(data), readingCanonical(received.getJSONObject("data")))
                assertTrue(reader.fetchSyncSnapshot().reading.any {
                    it.id == id && it.uuid == uuid && it.revision == revision
                })
            } finally {
                api.putReadingState(id, library, uuid, revision, initial.getJSONObject("data"))
            }
        } finally { auth.logout(token) }
    }

    @Test fun editMetadataAndReadingStatusWithConflictChecksAgainstDisposableDocker() = runBlocking {
        val path = System.getenv("MYBOOKS_TEST_WRITE_CREDENTIALS")
        assumeTrue("Set MYBOOKS_TEST_WRITE_CREDENTIALS to a disposable test server", !path.isNullOrBlank())
        val config = JSONObject(File(requireNotNull(path)).readText())
        val url = config.getString("url")
        require(url == "http://127.0.0.1:3081") { "Write test requires the disposable Docker endpoint" }
        val id = config.getLong("fixture_book_id")
        val auth = MyBooksAuthClient(url)
        val token = auth.login(config.getString("username"), config.getString("password"))
        val api = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
        try {
            val initial = JSONObject(api.fetchBookJson(id))
            val patch = JSONObject()
                .put("title", "Android fixture edit")
                .put("author", "Author A、Author B")
                .put("translators", JSONArray(listOf("Translator")))
                .put("summary", "One < two\nSecond line")
                .put("tags", JSONArray(listOf("Android fixture tag")))
                .put("rating", 8)
                .put("series", "Fixture series")
                .put("series_index", "2")
                .put("publisher", "Fixture publisher")
                .put("pub_year", "2021")
                .put("isbn", "9780140328721")
                .put("status", if (initial.getString("status") == "done") "reading" else "done")
            val saved = JSONObject(api.patchBook(id, patch, initial, token))
            for (key in patch.keys()) {
                if (key == "rating" || key == "series_index") assertEquals(patch.getDouble(key), saved.getDouble(key), 0.0)
                else assertEquals(key, patch.get(key).toString(), saved.get(key).toString())
            }
            for (stalePatch in listOf(JSONObject().put("title", "Stale title"), JSONObject().put("status", "wish"))) {
                val error = runCatching { api.patchBook(id, stalePatch, initial, token) }.exceptionOrNull() as? MyBooksHttpException
                assertEquals(409, error?.status)
            }
            val unread = JSONObject(api.patchBook(id, JSONObject().put("status", "wish"), saved, token))
            val wanted = JSONObject(api.patchBook(id, JSONObject().put("status", "want_to_read"), unread, token))
            assertEquals("want_to_read", wanted.getString("status"))
            val staleWants = runCatching {
                api.patchBook(id, JSONObject().put("status", "reading"), unread, token)
            }.exceptionOrNull() as? MyBooksHttpException
            assertEquals(409, staleWants?.status)
            val unmarked = JSONObject(api.patchBook(id, JSONObject().put("status", "wish"), wanted, token))
            assertEquals("wish", unmarked.getString("status"))
            val cleared = JSONObject(api.patchBook(id, JSONObject().put("tags", JSONArray()).put("pub_year", ""), unmarked, token))
            assertEquals(0, cleared.getJSONArray("tags").length())
            assertEquals("", cleared.getString("pub_year"))
            assertEquals(saved.getString("title"), cleared.getString("title"))
            assertEquals(unmarked.getString("status"), cleared.getString("status"))

            val organized = JSONObject(api.patchBook(id, JSONObject().put("favorite", true).put("folder", "Library.Fiction"), cleared, token))
            assertTrue(organized.getBoolean("favorite"))
            assertEquals("Library.Fiction", organized.getString("folder"))
            val reset = JSONObject(api.patchBook(id, JSONObject().put("favorite", false).put("folder", ""), organized, token))
            assertFalse(reset.getBoolean("favorite"))
            assertEquals("", reset.getString("folder"))
        } finally { auth.logout(token) }
    }

    @Test fun loginCatalogDetailsCoverAndEpubDownloadAgainstDocker() = runBlocking {
        val path = System.getenv("MYBOOKS_TEST_CREDENTIALS")
        assumeTrue("Set MYBOOKS_TEST_CREDENTIALS to a local test account file", !path.isNullOrBlank())
        val config = JSONObject(File(requireNotNull(path)).readText())
        val url = config.getString("url")
        require(url.startsWith("http://127.0.0.1:")) { "Live test is restricted to loopback" }
        val auth = MyBooksAuthClient(url)
        auth.testConnection()
        val token = auth.login(config.getString("username"), config.getString("password"))
        try {
            auth.checkSession(token)
            val api = MyBooksApiClient(serverUrlProvider = { url }, credentialProvider = { token })
            val catalog = api.fetchSyncSnapshot()
            assertTrue("Test library needs an EPUB fixture", catalog.books.isNotEmpty())
            val detail = parseRemoteBook(api.fetchBookJson(catalog.books.first().id), url)
            assertFalse(detail.uuid.isNullOrBlank())
            val epub = requireNotNull(detail.files.firstOrNull { it.format == "EPUB" })
            val target = File.createTempFile("mybooks-native-live-", ".epub")
            val cover = File.createTempFile("mybooks-native-cover-", ".jpg")
            try {
                val receipt = api.downloadFile(requireNotNull(epub.downloadUrl), target, epub.size) { _, _ -> }
                assertEquals(epub.size, receipt.size)
                assertEquals(64, receipt.sha256.length)
                ZipFile(target).use { zip -> assertNotNull(zip.getEntry("META-INF/container.xml")) }
                assertTrue(api.fetchCover(requireNotNull(detail.coverUrl), cover))
                assertTrue(cover.length() > 0L)
                val image = cover.readBytes()
                val jpeg = image.take(3) == listOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
                val png = image.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte())
                assertTrue("Cover endpoint returned a non-image response", jpeg || png)
                println("Docker native login/catalog/UUID/cover/EPUB checks passed")
            } finally { target.delete(); cover.delete() }
        } finally { auth.logout(token) }
    }
}
