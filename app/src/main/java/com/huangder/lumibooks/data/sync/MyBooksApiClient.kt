package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.sync.MyBooksConfig.SERVER_URL
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 服务端返回非 2xx 时保留状态码与响应体,便于 409 冲突合并。 */
class MyBooksHttpException(
    val status: Int,
    val body: String
) : Exception(parseServerMessage(body) ?: "请求失败（$status）")

private fun parseServerMessage(body: String): String? = runCatching {
    JSONObject(body).optTextOrNull("error")
}.getOrNull()

/**
 * 从 content:// 流式读取上传，避免整份读进内存（服务端单文件上限 80MB）。
 */
internal class ContentUriRequestBody(
    private val resolver: android.content.ContentResolver,
    private val uri: android.net.Uri,
    private val mediaType: MediaType? = "application/octet-stream".toMediaType()
) : RequestBody() {
    override fun contentType(): MediaType? = mediaType

    override fun writeTo(sink: okio.BufferedSink) {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        } ?: throw java.io.IOException("无法读取所选文件")
    }
}

/**
 * 新建服务器书目后服务端落库的结果。
 *
 * [title]/[author] 是服务端归一化后的值：本地正文副本必须用同一份，
 * 否则本地行会退回文件名/未知作者，下一轮同步再把这份文件来源的值推回服务器。
 */
data class MyBooksCreatedBook(
    val id: Long,
    val title: String,
    val author: String,
    val uuid: String? = null,
    val revision: Long? = null
)

data class MyBooksNotesPayload(
    val hasNotes: Boolean,
    val content: String,
    val size: Long,
    val updatedAt: String?
) {
    companion object {
        fun fromJson(json: String): MyBooksNotesPayload {
            val obj = JSONObject(json)
            return MyBooksNotesPayload(
                hasNotes = obj.optBoolean("has_notes", false),
                content = obj.optTextOrEmpty("content"),
                size = obj.optLong("size", 0L),
                updatedAt = obj.optTextOrNull("updated_at")
            )
        }
    }
}

/**
 * my-books 服务端客户端。
 *
 * 默认私有访问；设备会话来自服务器登录，APK 不内置管理令牌。
 * 不记录 token,不把它拼进 URL。
 */
class MyBooksApiClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build(),
    private val serverUrlProvider: () -> String = { MyBooksConfig.SERVER_URL }
) {
    private val SERVER_URL: String get() = serverUrlProvider()
    // 每次网络请求（包括重定向）都检查来源；设备凭据只发给当前服务器。
    private val client = client.newBuilder().addNetworkInterceptor { chain ->
        val request = chain.request()
        val server = SERVER_URL.toHttpUrl()
        val sameOrigin = request.url.scheme == server.scheme && request.url.host == server.host && request.url.port == server.port
        val builder = request.newBuilder()
        if (sameOrigin) {
            if (request.header("Authorization") == null && MyBooksConfig.adminToken.isNotBlank()) {
                builder.header("Authorization", "Bearer ${MyBooksConfig.adminToken}")
            }
        } else {
            builder.removeHeader("Authorization").removeHeader("X-Admin-Token")
        }
        chain.proceed(builder.build())
    }.build()
    private val jsonMediaType: MediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchSyncSnapshot(cursor: String? = null): MyBooksSyncSnapshot = withContext(Dispatchers.IO) {
        val url = SERVER_URL.toHttpUrl().newBuilder().addPathSegments("api/sync/changes")
            .apply { if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 410 && !cursor.isNullOrBlank()) return@withContext fetchSyncSnapshot(null)
            if (response.code == 404) return@withContext fetchLegacySyncSnapshot()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            parseMyBooksChanges(body, SERVER_URL)
        }
    }

    private suspend fun fetchLegacySyncSnapshot(): MyBooksSyncSnapshot {
        val request = Request.Builder().url("$SERVER_URL/api/sync/books").header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return MyBooksSyncSnapshot(fetchBooks())
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            return parseMyBooksSyncSnapshot(body, SERVER_URL)
        }
    }

    suspend fun fetchReadingState(id: Long, timeoutMs: Long = 0): JSONObject = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url("$SERVER_URL/api/sync/reading/$id").build())
        if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            JSONObject(body)
        }
    }

    suspend fun putReadingState(id: Long, libraryId: String, uuid: String, revision: Long, data: JSONObject, timeoutMs: Long = 0): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("library_id", libraryId).put("expected_uuid", uuid)
            .put("expected_revision", revision).put("data", data)
        val request = Request.Builder().url("$SERVER_URL/api/sync/reading/$id").put(jsonBody(body.toString())).build()
        val call = client.newCall(request)
        if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, responseBody)
            JSONObject(responseBody)
        }
    }

    /** 书架全量列表（不含 files）。 */
    suspend fun fetchBooks(): List<RemoteBook> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SERVER_URL/api/books")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("返回了空数据")
            try {
                parseRemoteBooks(body, SERVER_URL)
            } catch (error: Exception) {
                throw IllegalStateException("数据格式无法识别", error)
            }
        }
    }

    /** 拉取单本详情原始 JSON（含 files 与全部展示字段）。 */
    suspend fun fetchBookJson(id: Long): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SERVER_URL/api/books/$id")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("返回了空数据")
            body
        }
    }

    /** PATCH 局部更新;409 抛 [MyBooksHttpException],body 带当前书籍详情。 */
    suspend fun patchBook(
        id: Long,
        patch: JSONObject,
        token: String
    ): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "只读模式：未配置写入令牌" }
        val request = Request.Builder()
            .url("$SERVER_URL/api/books/$id")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .patch(jsonBody(patch.toString()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 409) throw MyBooksHttpException(409, body)
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            if (body.isBlank()) error("服务器返回了空数据")
            body
        }
    }

    /** 软删除到服务器回收站;404 视为已删除。 */
    suspend fun deleteBook(id: Long, token: String, expected: JSONObject): Boolean = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "只读模式：未配置写入令牌" }
        val request = Request.Builder()
            .url("$SERVER_URL/api/books/$id")
            .header("Authorization", "Bearer $token")
            .delete(jsonBody(expected.toString()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext true
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
            true
        }
    }

    /** 上传封面 data URL,返回服务端 /uploads/xxx。 */
    suspend fun uploadCover(dataUrl: String, token: String): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "只读模式：未配置写入令牌" }
        val payload = JSONObject().put("data", dataUrl)
        val request = Request.Builder()
            .url("$SERVER_URL/api/upload")
            .header("Authorization", "Bearer $token")
            .post(jsonBody(payload.toString()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            JSONObject(body).optTextOrNull("url")
                ?: error("服务器没有返回封面地址")
        }
    }

    /** 自定义字段定义(公开 GET)。 */
    suspend fun fetchCustomFields(): String = getRaw("/api/custom-fields")

    /** 新建服务器书目，返回新书 id 与服务端归一化后的书名/作者。 */
    suspend fun createBook(fields: JSONObject, token: String): MyBooksCreatedBook = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "只读模式：未配置写入令牌" }
        val request = Request.Builder()
            .url("$SERVER_URL/api/books")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(jsonBody(fields.toString()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            val json = JSONObject(body)
            val id = json.optLong("id", 0L).takeIf { it > 0L }
                ?: throw MyBooksHttpException(response.code, body)
            MyBooksCreatedBook(
                id = id,
                title = json.optTextOrEmpty("title"),
                author = normalizeRemoteAuthor(json.optTextOrEmpty("author")),
                uuid = json.optTextOrNull("uuid"),
                revision = json.optLong("revision", 0L).takeIf { it > 0 }
            )
        }
    }

    suspend fun fetchPortableState(): JSONObject = JSONObject(getRaw("/api/sync/state"))
    suspend fun putPortableState(libraryId: String, revision: Long, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$SERVER_URL/api/sync/state")
            .put(jsonBody(JSONObject().put("library_id", libraryId).put("expected_revision", revision).put("data", data).toString())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            JSONObject(body)
        }
    }
    suspend fun uploadPortableAsset(file: File, name: String) = withContext(Dispatchers.IO) {
        require(name.matches(Regex("[a-f0-9]{64}[.][a-z0-9]{1,10}")))
        val coroutine = kotlinx.coroutines.currentCoroutineContext()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer); if (count < 0) break; sink.write(buffer, 0, count)
                } }
            }
        }
        client.newCall(Request.Builder().url("$SERVER_URL/api/sync/state/assets/$name").put(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
        }
    }
    suspend fun downloadPortableAsset(name: String, target: File, size: Long) =
        downloadFile("$SERVER_URL/api/sync/state/assets/$name", target, size) { _, _ -> }

    suspend fun fileSha256(url: String): String? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(resolveUrl(SERVER_URL, url)).head().build()).execute().use { response ->
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
            response.header("X-Content-SHA256")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        }
    }

    /** 上传正文文件（multipart，字段名 file）。 */
    suspend fun uploadBookFile(
        remoteId: Long,
        fileName: String,
        content: RequestBody,
        token: String
    ): Boolean = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "只读模式：未配置写入令牌" }
        val payload = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, content)
            .build()
        val request = Request.Builder()
            .url("$SERVER_URL/api/books/$remoteId/file")
            .header("Authorization", "Bearer $token")
            .post(payload)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            true
        }
    }

    /** 读书心得(公开 GET)。 */
    suspend fun fetchNotes(id: Long): MyBooksNotesPayload = withContext(Dispatchers.IO) {
        MyBooksNotesPayload.fromJson(getRaw("/api/books/$id/notes"))
    }

    /** 保存读书心得;409 抛 [MyBooksHttpException]。 */
    suspend fun saveNotes(
        id: Long,
        content: String,
        expectedUpdatedAt: String?,
        token: String
    ): MyBooksNotesPayload = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "只读模式：未配置写入令牌" }
        val payload = JSONObject().put("content", content)
        if (!expectedUpdatedAt.isNullOrBlank()) payload.put("expected_updated_at", expectedUpdatedAt)
        val request = Request.Builder()
            .url("$SERVER_URL/api/books/$id/notes")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .put(jsonBody(payload.toString()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 409) throw MyBooksHttpException(409, body)
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            MyBooksNotesPayload.fromJson(if (body.isBlank()) "{}" else body)
        }
    }

    /** 按 ISBN 查元数据(豆瓣优先,Open Library 兜底);返回原始 JSON。 */
    suspend fun lookupIsbn(isbn: String): String =
        getRaw("/api/isbn/lookup?isbn=${encode(isbn)}")

    /** 按书名/ISBN 搜索豆瓣候选;返回原始 JSON。 */
    suspend fun searchMetadata(query: String): String =
        getRaw("/api/metadata/search?q=${encode(query)}")

    /**
     * 下载封面到 [dest]；失败（404/网络）返回 false，不抛异常。
     * 外链封面走服务器的 /api/img-proxy,避免豆瓣防盗链。
     */
    suspend fun fetchCover(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val finalUrl = if (url.startsWith(SERVER_URL)) {
                url
            } else {
                "$SERVER_URL/api/img-proxy?url=${encode(url)}"
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

    /** 流式下载到 [destination]，每 64KB 回报一次进度。 */
    suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSize: Long = -1L,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): MyBooksDownloadReceipt = withContext(Dispatchers.IO) {
        MyBooksResumableDownload(client).download(resolveUrl(SERVER_URL, url), destination, expectedSize, onProgress)
    }

    private suspend fun getRaw(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$SERVER_URL$path")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            if (body.isBlank()) error("返回了空数据")
            body
        }
    }

    private fun jsonBody(value: String): RequestBody =
        value.toRequestBody(jsonMediaType)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** 解析服务端 books 数组/单本(供测试与新调用方复用)。 */
internal fun parseServerNotesPayload(json: String): MyBooksNotesPayload =
    MyBooksNotesPayload.fromJson(json)

private fun serverError(code: Int): String = when (code) {
    400 -> "请求参数不正确（400）"
    401, 403 -> "无法写入服务器，请检查权限或更新 App（$code）"
    404 -> "找不到该资源（404）"
    409 -> "服务器内容已更新（409）"
    429 -> "请求过于频繁，请稍后再试"
    in 500..599 -> "网络请求出错（$code）"
    else -> "请求返回 $code"
}
