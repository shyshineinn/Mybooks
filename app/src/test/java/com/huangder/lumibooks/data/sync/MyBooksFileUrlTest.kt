package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MyBooksFileUrlTest {
    @Test fun checksumRequestsResolveRelativeUrlsAndKeepAbsoluteUrlsAndQueries() = runBlocking {
        val server = MockWebServer().apply { start() }
        val path = "/api/books/7/files/3/content?uuid=book%2Bone"
        val hash = "a".repeat(64)
        val api = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') })
        try {
            for (url in listOf(path, path.removePrefix("/"), server.url(path).toString())) {
                server.enqueue(MockResponse().setHeader("X-Content-SHA256", hash))
                assertEquals(hash, api.fileSha256(url))
                val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("HEAD", request.method); assertEquals(path, request.path)
            }
        } finally { server.shutdown() }
    }
    @Test fun relativeChecksumUrlsKeepTheConfiguredServerPathPrefix() = runBlocking {
        val server = MockWebServer().apply { start() }
        val api = MyBooksApiClient(serverUrlProvider = { server.url("/library/").toString() })
        try {
            server.enqueue(MockResponse().setHeader("X-Content-SHA256", "a".repeat(64)))
            assertEquals("a".repeat(64), api.fileSha256("/api/books/1/files/1/content?uuid=one"))
            assertEquals("/library/api/books/1/files/1/content?uuid=one", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
        } finally { server.shutdown() }
    }
    @Test fun relativeDownloadUrlUsesTheSameAbsoluteIdentityForVerifiedResume() = runBlocking {
        val server = MockWebServer().apply { start() }
        val directory = Files.createTempDirectory("mybooks-file-url-").toFile()
        val path = "/api/books/1/files/1/content?uuid=one"
        val body = "book contents".repeat(1000).toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val etag = "\"sha256-$hash\""
        val target = File(directory, "book.part")
        val api = MyBooksApiClient(serverUrlProvider = { server.url("/").toString().trimEnd('/') })
        try {
            target.writeBytes(body.copyOfRange(0, 1000))
            File(target.path + ".resume.json").writeText(JSONObject().put("url", server.url(path).toString())
                .put("etag", etag).put("sha256", hash).put("total", body.size).toString())
            server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(body.copyOfRange(1000, body.size)))
                .setHeader("Content-Range", "bytes 1000-${body.size - 1}/${body.size}")
                .setHeader("ETag", etag).setHeader("X-Content-SHA256", hash))
            val receipt = api.downloadFile(path, target, body.size.toLong()) { _, _ -> }
            assertArrayEquals(body, target.readBytes()); assertEquals(hash, receipt.sha256)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(path, request.path); assertEquals("bytes=1000-", request.getHeader("Range"))
            assertEquals(etag, request.getHeader("If-Range"))
        } finally { server.shutdown(); directory.deleteRecursively() }
    }
}
