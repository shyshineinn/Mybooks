package com.helloac.mybooks.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * my-books 服务端只读客户端。
 *
 * 只用 GET：书架列表、单本详情、封面与电子书下载都是公开读接口，
 * 不需要管理令牌，也不会向服务器写入任何阅读数据。
 */
class MyBooksApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
) {

    /** 书架全量列表（不含 files）。 */
    suspend fun fetchBooks(): List<RemoteBook> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${MyBooksConfig.SERVER_URL}/api/books")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(serverError(response.code))
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("服务器返回了空数据")
            try {
                parseRemoteBooks(body, MyBooksConfig.SERVER_URL)
            } catch (error: Exception) {
                throw IllegalStateException("服务器数据格式无法识别", error)
            }
        }
    }

    /** 拉取单本详情原始 JSON（含 files 与全部展示字段），由调用方负责缓存/解析。 */
    suspend fun fetchBookJson(id: Long): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${MyBooksConfig.SERVER_URL}/api/books/$id")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(serverError(response.code))
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("服务器返回了空数据")
            body
        }
    }

    /**
     * 下载封面到 [dest]；失败（404/网络）返回 false，不抛异常。
     * 外链封面（豆瓣图床等）直接下载会被防盗链拒绝，统一走服务器
     * 的 /api/img-proxy 代理取图。
     */
    suspend fun fetchCover(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val finalUrl = if (url.startsWith(MyBooksConfig.SERVER_URL)) {
                url
            } else {
                "${MyBooksConfig.SERVER_URL}/api/img-proxy?url=${URLEncoder.encode(url, "UTF-8")}"
            }
            val request = Request.Builder().url(finalUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    /**
     * 流式下载到 [destination]，每 64KB 回报一次进度。
     * 只在完整写完后返回，调用方负责 .part → 正式文件的重命名。
     */
    suspend fun downloadFile(
        url: String,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(serverError(response.code))
            val body = response.body ?: error("下载响应为空")
            val total = body.contentLength()
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= 64 * 1024) {
                            lastReported = written
                            onProgress(written, total)
                        }
                    }
                    output.flush()
                    onProgress(written, total)
                }
            }
        }
    }
}

private fun serverError(code: Int): String = when (code) {
    401, 403 -> "服务器拒绝了请求（$code）"
    404 -> "服务器上找不到该资源（404）"
    429 -> "请求过于频繁，请稍后再试"
    in 500..599 -> "服务器出错（$code）"
    else -> "服务器返回 $code"
}
