package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class MyBooksResumeDownloadTest {
    private val body = "complete-book-body-".repeat(6000).toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun response(bytes: ByteArray, whole: ByteArray = bytes): MockResponse = MockResponse().setBody(okio.Buffer().write(bytes))
        .setHeader("ETag", "\"sha256-" + hash(whole) + "\"").setHeader("X-Content-SHA256", hash(whole))
    private fun partial(target: File, url: String, bytes: ByteArray, whole: ByteArray = body) {
        target.writeBytes(bytes)
        File(target.path + ".resume.json").writeText(JSONObject().put("url", url).put("etag", "\"sha256-" + hash(whole) + "\"")
            .put("sha256", hash(whole)).put("total", whole.size).toString())
    }
    private fun fixture(block: suspend (MockWebServer, File, String, MyBooksResumableDownload) -> Unit) = runBlocking {
        val server = MockWebServer().apply { start() }
        val dir = Files.createTempDirectory("mybooks-resume-").toFile()
        try { block(server, File(dir, "book.part"), server.url("/book").toString(), MyBooksResumableDownload(OkHttpClient())) }
        finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun resumesAValidatedPrefixAndChecksTheWholeFile() = fixture { server, target, url, downloader ->
        partial(target, url, body.copyOfRange(0, 1000))
        server.enqueue(response(body.copyOfRange(1000, body.size), body).setResponseCode(206)
            .setHeader("Content-Range", "bytes 1000-" + (body.size - 1) + "/" + body.size))
        val receipt = downloader.download(url, target, body.size.toLong()) { _, _ -> }
        assertArrayEquals(body, target.readBytes()); assertEquals(hash(body), receipt.sha256)
        val request = server.takeRequest(); assertEquals("bytes=1000-", request.getHeader("Range"))
        assertEquals("\"sha256-" + hash(body) + "\"", request.getHeader("If-Range"))
    }
    @Test fun fileReplacementReturning200RestartsWithoutMixingOldBytes() = fixture { server, target, url, downloader ->
        partial(target, url, body.copyOfRange(0, 1000))
        val replacement = body.map { (it + 1).toByte() }.toByteArray()
        server.enqueue(response(replacement))
        downloader.download(url, target, replacement.size.toLong()) { _, _ -> }
        assertArrayEquals(replacement, target.readBytes())
    }
    @Test fun corruptPrefixIsRejectedInsteadOfPublishingACorruptBook() = fixture { server, target, url, downloader ->
        partial(target, url, ByteArray(1000) { 9 })
        server.enqueue(response(body.copyOfRange(1000, body.size), body).setResponseCode(206)
            .setHeader("Content-Range", "bytes 1000-" + (body.size - 1) + "/" + body.size))
        try { downloader.download(url, target, body.size.toLong()) { _, _ -> }; fail("checksum must fail") }
        catch (_: IOException) { }
        assertFalse(target.exists()); assertFalse(File(target.path + ".resume.json").exists())
    }
    @Test fun invalidRangeResponseIsDiscardedAndRetriedFromTheBeginning() = fixture { server, target, url, downloader ->
        partial(target, url, body.copyOfRange(0, 1000))
        server.enqueue(response(body.copyOfRange(1000, body.size), body).setResponseCode(206)
            .setHeader("Content-Range", "bytes 999-" + (body.size - 1) + "/" + body.size))
        server.enqueue(response(body))
        downloader.download(url, target, body.size.toLong()) { _, _ -> }
        assertArrayEquals(body, target.readBytes()); assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }
    @Test fun completePartialCanRecoverAfterAProcessStoppedBeforePublication() = fixture { server, target, url, downloader ->
        partial(target, url, body)
        server.enqueue(response(ByteArray(0), body).setResponseCode(416).setHeader("Content-Range", "bytes */" + body.size))
        assertEquals(hash(body), downloader.download(url, target, body.size.toLong()) { _, _ -> }.sha256)
    }
    @Test fun disconnectedBodyRetainsAResumablePrefix() = fixture { server, target, url, downloader ->
        server.enqueue(response(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try { downloader.download(url, target, body.size.toLong()) { _, _ -> }; fail("network must fail") }
        catch (_: IOException) { }
        val offset = target.length().toInt()
        assertTrue(offset in 1 until body.size); assertTrue(File(target.path + ".resume.json").isFile)
        server.enqueue(response(body.copyOfRange(offset, body.size), body).setResponseCode(206)
            .setHeader("Content-Range", "bytes " + offset + "-" + (body.size - 1) + "/" + body.size))
        downloader.download(url, target, body.size.toLong()) { _, _ -> }
        assertArrayEquals(body, target.readBytes()); server.takeRequest()
        assertEquals("bytes=" + offset + "-", server.takeRequest().getHeader("Range"))
    }
    @Test fun cancellingADownloadKeepsItsValidatedPrefixForTheNextAttempt() = fixture { server, target, url, downloader ->
        server.enqueue(response(body).throttleBody(1024, 200, java.util.concurrent.TimeUnit.MILLISECONDS))
        val job = kotlinx.coroutines.coroutineScope {
            val running = async(kotlinx.coroutines.Dispatchers.IO) { downloader.download(url, target, body.size.toLong()) { _, _ -> } }
            withTimeout(5000) { while (target.length() == 0L) delay(10) }
            running.cancelAndJoin()
            running
        }
        assertTrue(job.isCancelled)
        assertTrue(target.length() in 1 until body.size.toLong())
        assertTrue(File(target.path + ".resume.json").isFile)
    }

    @Test fun weakLegacyValidatorsAreNeverUsedToAppendBytes() = fixture { server, target, url, downloader ->
        target.writeBytes(ByteArray(1000) { 9 })
        File(target.path + ".resume.json").writeText(JSONObject().put("url", url).put("etag", "W/\"weak\"")
            .put("sha256", hash(body)).put("total", body.size).toString())
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)).setHeader("ETag", "W/\"weak\""))
        downloader.download(url, target, body.size.toLong()) { _, _ -> }
        assertNull(server.takeRequest().getHeader("Range")); assertArrayEquals(body, target.readBytes())
    }
}
