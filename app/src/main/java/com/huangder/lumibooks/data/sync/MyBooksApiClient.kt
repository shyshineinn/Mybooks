package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

/** Native mybooks-server access. The former custom backend protocol is retired. */
@Suppress("UNUSED_PARAMETER")
class MyBooksApiClient(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build(),
    private val serverUrlProvider: () -> String = { MyBooksConfig.SERVER_URL },
    private val credentialProvider: () -> String = { MyBooksConfig.adminToken }
) {
    private val SERVER_URL: String get() = MyBooksEndpoint.normalize(serverUrlProvider())
    private val libraryId: String get() = "mybooks:" + SERVER_URL.substringAfter("://")
    private val client = client.newBuilder().addNetworkInterceptor { chain ->
        val request = chain.request()
        val server = SERVER_URL.toHttpUrl()
        val sameOrigin = request.url.scheme == server.scheme && request.url.host == server.host && request.url.port == server.port
        val builder = request.newBuilder().removeHeader("Authorization").removeHeader("X-Admin-Token").removeHeader("Cookie")
        val credential = credentialProvider()
        if (sameOrigin && PoxenSession.isNative(credential)) {
            val cookies = PoxenSession.cookieHeader(credential, request.url)
            if (cookies.isNotBlank()) builder.header("Cookie", cookies)
        }
        chain.proceed(builder.build())
    }.build()
    private fun native() = PoxenApiClient(SERVER_URL, credentialProvider, client)

    suspend fun fetchSyncSnapshot(cursor: String? = null): MyBooksSyncSnapshot = withContext(Dispatchers.IO) {
        val catalog = try { native().catalogChanges(cursor) }
        catch (error: MyBooksHttpException) { if (error.status == 404) null else throw error }
        val books = if (catalog == null) native().books() else {
            val rows = catalog.getJSONArray("books")
            val ids = mutableSetOf<Long>()
            (0 until rows.length()).map { index ->
                val book = parseRemoteBook(rows.getJSONObject(index).toString(), SERVER_URL)
                require(book.id > 0 && !book.uuid.isNullOrBlank() && ids.add(book.id)) { "书库变更记录格式不正确" }
                book
            }
        }
        val nextCursor = catalog?.getString("cursor")?.also { require(it.isNotBlank() && it.length <= 4096) }
        val complete = catalog?.let { it.get("complete") as? Boolean ?: error("书库变更记录格式不正确") } ?: true
        val deleted = try { native().deletions() } catch (error: MyBooksHttpException) {
            // Older forks do not expose explicit deletion records.
            if (error.status == 404) emptyList() else throw error
        }
        var readingSupported = true
        val reading = try { native().readingHeads() } catch (error: MyBooksHttpException) {
            if (error.status == 404) {
                readingSupported = false
                emptyList()
            } else {
                throw error
            }
        }
        val activeUuids = books.mapNotNull { it.uuid }.toSet()
        val portableRevision = try { native().get("/api/user/app_portable?head=1").getLong("revision") }
        catch (error: MyBooksHttpException) { if (error.status == 404) null else throw error }
        MyBooksSyncSnapshot(books, deleted = deleted.filter { it.uuid !in activeUuids },
            libraryId = libraryId, syncVersion = if (catalog == null) 2 else 3, cursor = nextCursor, isFull = complete,
            supportsReadingSync = readingSupported, reading = reading,
            supportsPortableSync = portableRevision != null, portableRevision = portableRevision ?: 0)
    }
    suspend fun fetchBooks(): List<RemoteBook> = withContext(Dispatchers.IO) { native().books() }
    suspend fun fetchBookJson(id: Long): String = withContext(Dispatchers.IO) { native().book(id) }

    // Keep local editing/reading data intact until the corresponding native sync is implemented.
    // No retired endpoint is called as a fallback.
    private fun unsupported(): Nothing = throw PoxenWriteUnavailable()
    suspend fun fetchReadingState(id: Long, timeoutMs: Long = 0): JSONObject = withContext(Dispatchers.IO) {
        PoxenApiClient(SERVER_URL, credentialProvider, client).readingState(id)
    }
    suspend fun putReadingState(id: Long, libraryId: String, uuid: String, revision: Long, data: JSONObject,
        timeoutMs: Long = 0): JSONObject = withContext(Dispatchers.IO) {
        PoxenApiClient(SERVER_URL, credentialProvider, client).putReadingState(id, libraryId, uuid, revision, data)
    }
    internal suspend fun patchBook(id: Long, patch: JSONObject, current: JSONObject, token: String): String =
        withContext(Dispatchers.IO) {
            PoxenApiClient(SERVER_URL, { token }, client).editBook(id, patch, current)
        }
    suspend fun deleteBook(id: Long, token: String, expected: JSONObject): Boolean = withContext(Dispatchers.IO) {
        if (PoxenSession.isNative(token)) {
            val uuid = expected.optString("expected_uuid")
            val updatedAt = expected.optString("expected_updated_at")
            require(uuid.isNotBlank() && updatedAt.isNotBlank()) { "缺少服务器书目版本记录" }
            PoxenApiClient(SERVER_URL, { token }, client).deleteBook(id, uuid, updatedAt)
        } else unsupported()
    }
    suspend fun uploadCover(dataUrl: String, token: String): String = unsupported()
    suspend fun createBook(fields: JSONObject, token: String): MyBooksCreatedBook = unsupported()
    suspend fun fetchPortableState(): JSONObject = withContext(Dispatchers.IO) {
        native().get("/api/user/app_portable").put("library_id", libraryId)
    }
    suspend fun putPortableState(libraryId: String, revision: Long, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        require(libraryId == this@MyBooksApiClient.libraryId)
        native().putPortableState(revision, data).put("library_id", libraryId)
    }
    suspend fun uploadPortableAsset(file: File, name: String) = withContext(Dispatchers.IO) {
        native().uploadPortableAsset(file, name)
    }
    suspend fun downloadPortableAsset(name: String, target: File, size: Long): MyBooksDownloadReceipt {
        require(portableAssetName.matches(name))
        return downloadFile("$SERVER_URL/api/user/app_portable/assets/$name", target, size) { _, _ -> }
    }
    suspend fun uploadBookFile(remoteId: Long, fileName: String, content: RequestBody, token: String): Boolean = unsupported()
    internal suspend fun uploadNativeBook(file: File, fileName: String, sha256: String,
        onProgress: (Long, Long) -> Unit): PoxenUploadResult = withContext(Dispatchers.IO) {
        PoxenApiClient(SERVER_URL, credentialProvider, client).uploadBook(file, fileName, sha256, onProgress)
    }
    internal suspend fun addNativeBookFormat(bookId: Long, file: File, fileName: String, sha256: String,
        onProgress: (Long, Long) -> Unit): PoxenUploadResult = withContext(Dispatchers.IO) {
        PoxenApiClient(SERVER_URL, credentialProvider, client).addBookFormat(bookId, file, fileName, sha256, onProgress)
    }
    internal suspend fun uploadNativeCover(bookId: Long, bytes: ByteArray, current: JSONObject? = null) = withContext(Dispatchers.IO) {
        PoxenApiClient(SERVER_URL, credentialProvider, client).uploadCover(bookId, bytes, current = current)
    }
    suspend fun lookupIsbn(isbn: String): String = withContext(Dispatchers.IO) {
        PoxenMetadata.lookup(native().refer(isbn = isbn), isbn)
    }
    suspend fun searchMetadata(query: String): String = withContext(Dispatchers.IO) {
        PoxenMetadata.search(native().refer(title = query))
    }

    suspend fun fileSha256(url: String): String? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(resolveUrl(SERVER_URL, url)).head().build()).execute().use { response ->
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, response.body?.string().orEmpty())
            response.header("X-Content-SHA256")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        }
    }
    suspend fun fetchCover(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(resolveUrl(SERVER_URL, url)).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                dest.parentFile?.mkdirs()
                body.byteStream().use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    suspend fun downloadFile(url: String, destination: File, expectedSize: Long = -1L,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): MyBooksDownloadReceipt = withContext(Dispatchers.IO) {
        MyBooksResumableDownload(client).download(resolveUrl(SERVER_URL, url), destination, expectedSize, onProgress)
    }
}
