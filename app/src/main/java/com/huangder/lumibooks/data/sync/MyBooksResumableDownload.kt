package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class MyBooksDownloadReceipt(val sha256: String, val size: Long)

/** Resume only against a strong validator and verify the whole completed file, including its prefix. */
internal class MyBooksResumableDownload(private val client: OkHttpClient) {
    suspend fun download(url: String, target: File, expectedSize: Long,
        progress: (Long, Long) -> Unit): MyBooksDownloadReceipt = coroutineScope {
        val metadata = File(target.path + ".resume.json")
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val saved = runCatching { JSONObject(metadata.readText()) }.getOrNull()
            val offset = target.takeIf { it.isFile && saved != null && saved.optString("url") == url &&
                isStrong(saved.optString("etag")) && saved.optString("sha256").matches(Regex("[a-f0-9]{64}")) &&
                it.length() in 1..saved.optLong("total") }?.length() ?: 0L
            val builder = Request.Builder().url(url)
            if (offset > 0L) builder.header("Range", "bytes=$offset-").header("If-Range", saved!!.getString("etag"))
            val call = client.newCall(builder.build())
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    val etag = response.header("ETag").orEmpty()
                    val hash = response.header("X-Content-SHA256").orEmpty().lowercase()
                    if (response.code == 416 && offset > 0 && saved != null && etag == saved.optString("etag") &&
                        hash == saved.optString("sha256") && offset == saved.optLong("total") &&
                        (expectedSize <= 0 || offset == expectedSize) && digest(target) == hash) {
                        progress(offset, offset)
                        return@coroutineScope MyBooksDownloadReceipt(hash, offset)
                    }
                    if (response.code == 416 && attempt == 0) {
                        target.delete(); metadata.delete(); return@repeat
                    }
                    if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
                    val body = response.body ?: throw IOException("下载响应为空")
                    var append = false
                    var total = body.contentLength()
                    if (response.code == 206) {
                        val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        val begin = range?.groupValues?.get(1)?.toLongOrNull()
                        val end = range?.groupValues?.get(2)?.toLongOrNull()
                        total = range?.groupValues?.get(3)?.toLongOrNull() ?: -1L
                        val valid = offset > 0 && saved != null && begin == offset && end != null && total > end && end >= offset &&
                            etag == saved?.optString("etag") && hash == saved.optString("sha256") &&
                            total == saved.optLong("total") && (body.contentLength() < 0 || body.contentLength() == end - offset + 1)
                        if (!valid) {
                            target.delete(); metadata.delete()
                            if (attempt == 0) return@repeat
                            throw IOException("服务器返回了无效的续传范围")
                        }
                        append = true
                    }
                    val resumable = isStrong(etag) && hash.matches(Regex("[a-f0-9]{64}")) && total > 0L
                    target.parentFile?.mkdirs()
                    var written = if (append) offset else 0L
                    FileOutputStream(target, append).use { output ->
                        if (resumable) {
                            val tmp = File(metadata.path + ".tmp")
                            tmp.writeText(JSONObject().put("url", url).put("etag", etag).put("sha256", hash).put("total", total).toString())
                            if (!tmp.renameTo(metadata)) { metadata.delete(); if (!tmp.renameTo(metadata)) throw IOException("无法保存下载进度") }
                        } else metadata.delete()
                        progress(written, total)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count); written += count
                                if (total >= 0 && written > total) {
                                    target.delete(); metadata.delete(); throw IOException("下载长度超过声明值")
                                }
                                progress(written, total)
                            }
                        }
                        output.flush()
                    }
                    if ((total >= 0 && written != total) || (expectedSize > 0 && written != expectedSize)) {
                        throw IOException("正文大小已变化或下载尚未完成，请重试")
                    }
                    val actualHash = digest(target)
                    if (hash.isNotBlank() && hash != actualHash) {
                        target.delete(); metadata.delete(); throw IOException("正文校验失败，请重新下载")
                    }
                    progress(written, written)
                    return@coroutineScope MyBooksDownloadReceipt(actualHash, written)
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                throw error
            } finally { cancellation.cancel() }
        }
        throw IOException("无法恢复下载，请重试")
    }

    private fun isStrong(etag: String) = etag.startsWith('"') && etag.endsWith('"') && !etag.startsWith("W/")
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
