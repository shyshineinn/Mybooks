package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class PoxenApiClientTest {
    private fun session(server: MockWebServer): String {
        val cookie = Cookie.Builder().name("user_id").value("test-session").hostOnlyDomain(server.hostName).path("/").build()
        return PoxenSession.encode(server.url("/").toString(), "reader", listOf(cookie))
    }

    @Test fun nativeLoginUsesFormAndCookiesWithoutPersistingPassword() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"err":"installed","installed":true}"""))
            server.enqueue(MockResponse().addHeader("Set-Cookie", "user_id=signed-session; Path=/; HttpOnly")
                .setBody("""{"err":"ok"}"""))
            server.enqueue(MockResponse().setBody("""{"err":"ok","user":{"is_login":true}}"""))
            val auth = MyBooksAuthClient(server.url("/").toString())
            auth.testConnection()
            val token = auth.login("reader", "private-password")
            assertTrue(PoxenSession.isNative(token))
            assertFalse(String(java.util.Base64.getUrlDecoder().decode(token.substringAfter(':'))).contains("private-password"))
            assertEquals("/api/admin/install", server.takeRequest().path)
            val login = server.takeRequest()
            assertEquals("/api/user/sign_in", login.path)
            assertEquals("username=reader&password=private-password", login.body.readUtf8())
            assertNull(login.getHeader("Authorization"))
            val who = server.takeRequest()
            assertEquals("/api/user/info", who.path)
            assertEquals("user_id=signed-session", who.getHeader("Cookie"))
        } finally { server.shutdown() }
    }

    @Test fun sessionCannotBeSentToAnotherOriginOrOutsideBasePath() {
        val origin = "https://books.example.com/library"
        val cookie = Cookie.Builder().name("user_id").value("secret").hostOnlyDomain("books.example.com").path("/").secure().build()
        val token = PoxenSession.encode(origin, "reader", listOf(cookie))
        assertEquals("user_id=secret", PoxenSession.cookieHeader(token, "$origin/api/all".toHttpUrl()))
        for (url in listOf("https://evil.example/library/api/all", "http://books.example.com/library/api/all",
            "https://books.example.com:444/library/api/all", "https://books.example.com/other/api/all")) {
            assertEquals("", PoxenSession.cookieHeader(token, url.toHttpUrl()))
        }
    }

    @Test fun paginatedCatalogUsesNativeRoutesAndExplicitDeletionFeed() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val token = session(server)
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"err":"ok","user":{"is_login":true}}"""))
            server.enqueue(MockResponse().setBody("""{"err":"ok","total":2,"books":[{"id":3,"uuid":"first-book","title":"First","authors":["A"]}]}"""))
            server.enqueue(MockResponse().setBody("""{"err":"ok","total":2,"books":[{"id":2,"uuid":"second-book","title":"Second"}]}"""))
            server.enqueue(MockResponse().setBody("""{"err":"ok","deletions":[{"book_id":3,"uuid":"first-book","deleted_at":"2026-11-01T00:00:00Z"},{"book_id":9,"uuid":"deleted-book","deleted_at":"2026-11-01T00:00:00Z"}]}"""))
            val api = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') }, credentialProvider = { token })
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"err":"ok","revision":4}"""))
            val snapshot = api.fetchSyncSnapshot("old-cursor")
            assertEquals(listOf(3L, 2L), snapshot.books.map { it.id })
            assertTrue(snapshot.isFull)
            assertEquals(2, snapshot.syncVersion)
            assertEquals("mybooks:" + server.url("/").toString().trimEnd('/').substringAfter("://"), snapshot.libraryId)
            assertFalse(snapshot.supportsReadingSync)
            assertTrue(snapshot.supportsPortableSync)
            assertEquals(4L, snapshot.portableRevision)
            assertEquals(listOf(9L), snapshot.deleted.map { it.id })
            assertEquals("/api/book/catalog_changes?cursor=old-cursor", server.takeRequest().path)
            assertEquals("/api/user/info", server.takeRequest().path)
            assertEquals("/api/all?start=0&size=200", server.takeRequest().path)
            assertEquals("/api/all?start=1&size=200", server.takeRequest().path)
            assertEquals("/api/book/deletions", server.takeRequest().path)
            assertEquals("/api/book/reading_sync/heads", server.takeRequest().path)
            assertEquals("/api/user/app_portable?head=1", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun nativeCoverUploadUsesTheServerCoverRoute() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"err":"ok"}"""))
            val api = PoxenApiClient(server.url("/").toString().trimEnd('/'), { session(server) }, OkHttpClient())
            api.uploadCover(9, byteArrayOf(1, 2, 3), current = JSONObject()
                .put("uuid", "test-book").put("cover_version", "original"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/book/9/cover", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"expected_uuid\"") && body.contains("test-book"))
            assertTrue(body.contains("name=\"expected_cover_version\"") && body.contains("original"))
        } finally { server.shutdown() }
    }

    @Test fun nativeDetailPreservesFileVersionWithoutUsingTheSignedUrlAsIdentity() {
        val detail = normalizePoxenBook(JSONObject(
            """{"id":9,"title":"Book","files":[{"format":"EPUB","href":"/api/book/9.epub?dl=temporary","size":10,"file_version":"10:123:456:7"}]}"""
        ))
        val file = parseRemoteBook(detail.toString(), "http://localhost").files.single()
        assertEquals("10:123:456:7", file.version)
    }

    @Test fun largeBookUsesChunkedUploadAndReturnsServerHash() = runBlocking {
        val server = MockWebServer()
        val file = File.createTempFile("mybooks-chunk-", ".epub")
        try {
            server.start()
            file.outputStream().use { output ->
                val block = ByteArray(64 * 1024)
                repeat((8 * 1024 * 1024) / block.size) { output.write(block) }
                output.write(1)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse().setBody("""{"err":"ok","received_chunks":1,"total_chunks":2}"""))
            server.enqueue(MockResponse().setBody("""{"err":"ok","book_id":7,"sha256":"$hash"}"""))

            val api = PoxenApiClient(server.url("/").toString().trimEnd('/'), { session(server) }, OkHttpClient())
            val result = api.uploadBook(file, "book.epub", hash)
            assertEquals(7L, result.bookId)
            assertEquals(hash, result.sha256)
            assertEquals("/api/book/upload/chunk", server.takeRequest().path)
            assertEquals("/api/book/upload/chunk", server.takeRequest().path)
        } finally {
            file.delete()
            server.shutdown()
        }
    }

    @Test fun doubanLookupUsesServerReferAndMapsCandidates() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val token = session(server)
            val api = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') }, credentialProvider = { token })
            val candidate = """{"title":"三体","authors":["刘慈欣"],"comments":"<p>简介</p>","publisher":"重庆出版社","pubyear":"2008","isbn":"9787536692930","isbn13":"9787536692930","rating":9.3,"cover_url":"https://img.example/cover.jpg","provider_key":"douban","provider_value":"2567698","tags":["科幻"]}"""
            server.enqueue(MockResponse().setBody("""{"err":"ok","books":[$candidate]}"""))
            val lookup = JSONObject(api.lookupIsbn("978-7-5366-9293-0"))
            assertEquals("found", lookup.getString("status"))
            val book = lookup.getJSONObject("book")
            assertEquals("三体", book.getString("title"))
            assertEquals("刘慈欣", book.getString("author"))
            assertEquals("简介", book.getString("summary"))
            assertEquals("2008", book.getString("pub_year"))
            assertEquals("2567698", book.getString("douban_id"))
            assertEquals("https://img.example/cover.jpg", book.getString("cover"))
            assertEquals("科幻", book.getJSONArray("tags").getString(0))
            assertEquals("/api/book/0/refer?isbn=9787536692930", server.takeRequest().path)

            server.enqueue(MockResponse().setBody("""{"err":"ok","books":[]}"""))
            assertEquals("not_found", JSONObject(api.lookupIsbn("9787536692930")).getString("status"))
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"err":"ok","books":[$candidate]}"""))
            val search = JSONObject(api.searchMetadata("三体"))
            assertEquals("三体", search.getJSONArray("results").getJSONObject(0).getString("title"))
            val searchPath = requireNotNull(server.takeRequest().path)
            assertTrue(searchPath.startsWith("/api/book/0/refer?title="))
            assertTrue(searchPath.contains("%E4%B8%89%E4%BD%93"))
        } finally { server.shutdown() }
    }

    @Test fun deletionsAndGuardedDeleteUseNativeRoutes() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val api = PoxenApiClient(server.url("/").toString().trimEnd('/'), { session(server) }, OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"err":"ok","deletions":[{"book_id":9,"uuid":"restored-book","deleted_at":"2026-11-01T00:00:00Z"}]}"""))
            assertEquals(listOf(9L), api.deletions().map { it.id })
            assertEquals("/api/book/deletions", server.takeRequest().path)

            server.enqueue(MockResponse().setBody("""{"err":"ok"}"""))
            assertTrue(api.deleteBook(9, "deleted-book", "2026-11-01T00:00:00Z"))
            val delete = server.takeRequest()
            assertEquals("POST", delete.method)
            assertEquals("/api/book/9/delete", delete.path)
            val body = JSONObject(delete.body.readUtf8())
            assertEquals("deleted-book", body.getString("expected_uuid"))
            assertEquals("2026-11-01T00:00:00Z", body.getString("expected_last_modified"))

            server.enqueue(MockResponse().setBody("""{"err":"not_found","msg":"gone"}"""))
            assertTrue(api.deleteBook(9, "deleted-book", "2026-11-01T00:00:00Z"))
        } finally { server.shutdown() }
    }

    @Test fun incompleteCatalogAndExpiredSessionFailInsteadOfReturningAnEmptyLibrary() {
        val server = MockWebServer()
        try {
            server.start()
            val api = PoxenApiClient(server.url("/").toString().trimEnd('/'), { session(server) }, OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"err":"ok","user":{"is_login":false}}"""))
            try { api.books(); fail("Expired session accepted") } catch (error: MyBooksHttpException) { assertEquals(401, error.status) }
            server.enqueue(MockResponse().setBody("""{"err":"ok","user":{"is_login":true}}"""))
            server.enqueue(MockResponse().setBody("""{"err":"ok","total":2,"books":[]}"""))
            try { api.books(); fail("Incomplete catalog accepted") } catch (_: IllegalArgumentException) { }
        } finally { server.shutdown() }
    }

    @Test fun detailMapsFieldsAndRequiresStableBookIdentity() {
        val server = MockWebServer()
        try {
            server.start()
            val api = PoxenApiClient(server.url("/").toString().trimEnd('/'), { session(server) }, OkHttpClient())
            val book = """{"id":4,"uuid":"stable-id","title":"Book","authors":["A","B"],"translators":["T"],"comments":"<p>Summary</p>","img":"/get/cover/4.jpg","category":"Fiction","folder":"Library.Fiction","state":{"read_state":2,"favorite":1,"wants":true},"files":[{"format":"EPUB","size":7,"href":"/api/book/4.EPUB"}]}"""
            server.enqueue(MockResponse().setBody("""{"err":"ok","book":$book}"""))
            val normalized = JSONObject(api.book(4))
            assertEquals("A、B", normalized.getString("author"))
            assertEquals("done", normalized.getString("status"))
            assertEquals("Summary", normalized.getString("summary").trim())
            val display = ServerBookMeta.fromJson(normalized.toString(), 4)
            assertTrue(display.serverFavorite)
            assertEquals("Fiction", display.serverCategory)
            assertEquals("Library.Fiction", normalized.getString("folder"))
            assertEquals("/api/book/4.EPUB", normalized.getJSONArray("files").getJSONObject(0).getString("download_url"))
            assertFalse(normalized.has("revision"))
            val unread = normalizePoxenBook(JSONObject(book).put("state", JSONObject().put("read_state", 0)))
            assertEquals("wish", unread.getString("status"))
            val wanted = normalizePoxenBook(JSONObject(book).put("state", JSONObject().put("read_state", 0).put("wants", 1)))
            assertEquals("want_to_read", wanted.getString("status"))
            server.enqueue(MockResponse().setBody("""{"err":"ok","book":{"id":4,"title":"No UUID"}}"""))
            try { api.book(4); fail("Missing UUID accepted") } catch (_: IllegalArgumentException) { }
        } finally { server.shutdown() }
    }

    @Test fun metadataWriteRequiresGuardSupportBeforeSendingAnyRequest() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val api = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') }, credentialProvider = { session(server) })
            val current = JSONObject().put("id", 1).put("uuid", "stable-id")
            val error = runCatching { api.patchBook(1, JSONObject().put("title", "changed"), current, session(server)) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }

    @Test fun redirectsDoNotForwardSessionCookies() = runBlocking {
        val source = MockWebServer()
        val destination = MockWebServer()
        try {
            source.start(); destination.start()
            val token = session(source)
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/collect")))
            destination.enqueue(MockResponse().setBody("image"))
            val api = MyBooksApiClient(serverUrlProvider = { source.url("/").toString().trimEnd('/') }, credentialProvider = { token })
            val temp = java.io.File.createTempFile("poxen-cover-", ".tmp")
            try { api.downloadFile(source.url("/get/cover/1.jpg").toString(), temp, 5) { _, _ -> } }
            finally { temp.delete() }
            assertNotNull(source.takeRequest().getHeader("Cookie"))
            val redirected = destination.takeRequest()
            assertNull(redirected.getHeader("Cookie"))
            assertNull(redirected.getHeader("Authorization"))
        } finally { source.shutdown(); destination.shutdown() }
    }
}
