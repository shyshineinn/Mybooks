package com.huangder.lumibooks.data.sync

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.Base64

internal val portableAssetName = Regex("[a-f0-9]{64}\\.[a-z0-9]{1,10}")

/** Cookie session is stored inside the existing Keystore-encrypted credential store. */
internal object PoxenSession {
    private const val PREFIX = "poxen-cookie-v1:"
    fun isNative(token: String) = token.startsWith(PREFIX)

    fun encode(baseUrl: String, username: String, cookies: List<Cookie>): String {
        require(cookies.any { it.name == "user_id" }) { "登录响应缺少会话" }
        val data = JSONObject().put("server", MyBooksEndpoint.normalize(baseUrl)).put("username", username)
            .put("cookies", JSONArray(cookies.map(Cookie::toString)))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(data.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decode(token: String): JSONObject {
        require(isNative(token))
        return JSONObject(String(Base64.getUrlDecoder().decode(token.removePrefix(PREFIX)), Charsets.UTF_8))
    }

    fun cookieHeader(token: String, url: HttpUrl): String {
        if (!isNative(token)) throw MyBooksHttpException(401, """{"error":"请登录 mybooks 服务端"}""")
        val data = decode(token)
        val origin = data.getString("server").toHttpUrl()
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            !(url.encodedPath == origin.encodedPath.trimEnd('/') ||
                url.encodedPath.startsWith(origin.encodedPath.trimEnd('/') + "/"))) return ""
        val now = System.currentTimeMillis()
        val cookies = data.getJSONArray("cookies")
        return (0 until cookies.length()).mapNotNull { Cookie.parse(origin, cookies.getString(it)) }
            .filter { it.expiresAt > now && it.matches(url) }
            .joinToString("; ") { it.name + "=" + it.value }
    }

}

internal class PoxenWriteUnavailable : IllegalStateException("此操作尚未接入 mybooks 服务端")

internal data class PoxenUploadResult(val bookId: Long, val sha256: String?)
internal const val POXEN_UPLOAD_CHUNK_SIZE = 8 * 1024 * 1024

internal val poxenWritableBookFields = setOf(
    "title", "author", "translators", "summary", "tags", "rating", "series",
    "series_index", "publisher", "pub_year", "isbn", "status", "read_logs", "favorite", "folder"
)

/** Native upstream API. It does not emulate the old server's synchronization protocol. */
internal class PoxenApiClient(
    private val baseUrl: String,
    private val token: () -> String,
    client: OkHttpClient
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    fun get(path: String): JSONObject = request(path)

    fun catalogChanges(cursor: String?): JSONObject =
        get("/api/book/catalog_changes" + (cursor?.let { "?cursor=" + encode(it) } ?: ""))

    fun putPortableState(revision: Long, data: JSONObject): JSONObject =
        post("/api/user/app_portable", JSONObject().put("expected_revision", revision).put("data", data))

    fun uploadPortableAsset(file: File, name: String) {
        require(portableAssetName.matches(name))
        request("/api/user/app_portable/assets/$name", file.asRequestBody("application/octet-stream".toMediaType()))
    }

    private fun post(path: String, data: JSONObject): JSONObject =
        request(path, data.toString().toRequestBody("application/json".toMediaType()))

    private fun request(path: String, payload: RequestBody? = null): JSONObject {
        val url = (baseUrl + path).toHttpUrl()
        val credential = PoxenSession.cookieHeader(token(), url)
        if (credential.isBlank()) throw MyBooksHttpException(401, """{"error":"登录已失效，请重新登录"}""")
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Cookie", credential)
            .apply { if (payload != null) post(payload) }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, body)
            return checkedPoxenResponse(body)
        }
    }

    fun assertLoggedIn() {
        val user = get("/api/user/info").optJSONObject("user")
        if (user?.optBoolean("is_login") != true) throw MyBooksHttpException(401, """{"error":"登录已失效，请重新登录"}""")
    }

    fun books(): List<RemoteBook> {
        assertLoggedIn()
        val books = linkedMapOf<Long, RemoteBook>()
        var start = 0
        while (true) {
            val page = get("/api/all?start=$start&size=200")
            val rows = page.getJSONArray("books")
            val total = page.getLong("total")
            require(total >= 0) { "书库返回的数量无效" }
            for (index in 0 until rows.length()) {
                val book = parseRemoteBook(normalizePoxenBook(rows.getJSONObject(index)).toString(), baseUrl)
                require(books.put(book.id, book) == null) { "书库分页期间发生变化，请重新同步" }
            }
            start += rows.length()
            if (start >= total) return books.values.toList()
            require(rows.length() > 0) { "书库分页不完整，请重试" }
        }
    }

    fun book(id: Long): String {
        val book = get("/api/book/$id").getJSONObject("book")
        require(book.getLong("id") == id) { "书籍身份不匹配" }
        // Fork exposes Calibre UUID; never synthesize identity from a mutable numeric ID.
        require(book.optString("uuid").isNotBlank()) { "请更新 fork 服务端以提供书籍 UUID" }
        return normalizePoxenBook(book).toString()
    }

    /** Metadata candidates from the server's enabled sources; the ID is unused when a query is present. */
    fun refer(isbn: String? = null, title: String? = null): JSONObject {
        val params = buildList {
            val normalizedIsbn = isbn?.trim()?.filter { it.isDigit() || it == 'X' || it == 'x' }.orEmpty()
            if (normalizedIsbn.isNotBlank()) add("isbn=" + encode(normalizedIsbn))
            if (!title.isNullOrBlank()) add("title=" + encode(title.trim()))
        }
        require(params.isNotEmpty()) { "缺少查询条件" }
        return get("/api/book/0/refer?" + params.joinToString("&"))
    }

    fun readingState(bookId: Long): JSONObject = get("/api/book/$bookId/reading_sync")

    fun putReadingState(bookId: Long, libraryId: String, expectedUuid: String,
        expectedRevision: Long, data: JSONObject): JSONObject {
        val payload = JSONObject().put("library_id", libraryId)
            .put("expected_uuid", expectedUuid)
            .put("expected_revision", expectedRevision)
            .put("data", data)
        return putReading("/api/book/$bookId/reading_sync", payload)
    }

    fun readingHeads(): List<MyBooksReadingHead> {
        val rows = get("/api/book/reading_sync/heads").optJSONArray("reading") ?: JSONArray()
        val ids = mutableSetOf<Long>()
        val out = ArrayList<MyBooksReadingHead>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.getLong("id")
            val uuid = row.optString("uuid").trim()
            val revision = row.getLong("revision")
            require(id > 0 && uuid.isNotBlank() && revision > 0 && ids.add(id)) { "阅读同步记录格式不正确" }
            out += MyBooksReadingHead(id, uuid, revision)
        }
        return out
    }

    /** Preserve the server's `current` payload on a revision conflict for the three-way merge. */
    private fun putReading(path: String, payload: JSONObject): JSONObject {
        val url = (baseUrl + path).toHttpUrl()
        val credential = PoxenSession.cookieHeader(token(), url)
        if (credential.isBlank()) throw MyBooksHttpException(401, """{"error":"登录已失效，请重新登录"}""")
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cookie", credential)
            .put(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, text)
            val data = JSONObject(text)
            val err = data.optString("err")
            if (err == "ok") return data
            val body = JSONObject().put("error", data.optString("msg").ifBlank { err })
            data.optJSONObject("current")?.let { body.put("current", it) }
            throw MyBooksHttpException(if (err == "book.conflict") 409 else poxenErrorStatus(err), body.toString())
        }
    }

    /** Current server-side deletions. Active books are filtered by the caller. */
    fun deletions(): List<MyBooksDeletion> {
        val rows = get("/api/book/deletions").optJSONArray("deletions") ?: JSONArray()
        val ids = mutableSetOf<Long>()
        val uuids = mutableSetOf<String>()
        val out = ArrayList<MyBooksDeletion>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.getLong("book_id")
            val uuid = row.optString("uuid").trim()
            val deletedAt = row.optString("deleted_at").trim()
            require(id > 0 && uuid.isNotBlank() && deletedAt.isNotBlank()) { "删除记录格式不正确" }
            require(ids.add(id) && uuids.add(uuid)) { "删除记录存在重复书目" }
            Instant.parse(deletedAt)
            out += MyBooksDeletion(id, uuid, 0L, deletedAt)
        }
        return out
    }

    /** Idempotent guarded delete; a missing book is already deleted. */
    fun deleteBook(id: Long, expectedUuid: String, expectedLastModified: String): Boolean {
        require(id > 0 && expectedUuid.isNotBlank() && expectedLastModified.isNotBlank())
        val payload = JSONObject().put("expected_uuid", expectedUuid)
            .put("expected_last_modified", expectedLastModified)
        return try {
            post("/api/book/$id/delete", payload)
            true
        } catch (error: MyBooksHttpException) {
            if (error.status == 404) true else throw error
        }
    }

    fun uploadBook(file: File, fileName: String, sha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }): PoxenUploadResult =
        if (file.length() <= POXEN_UPLOAD_CHUNK_SIZE) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("ebook", fileName, SliceRequestBody(file, 0, file.length(), onProgress))
                .build()
            uploadResult(multipart("/api/book/upload", body))
        } else {
            uploadChunked(file, fileName, sha256, onProgress)
        }

    /** Add a format to an existing book. Large retries fall back to the same-title merge path. */
    fun addBookFormat(bookId: Long, file: File, fileName: String, sha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }): PoxenUploadResult {
        require(bookId > 0)
        if (file.length() > POXEN_UPLOAD_CHUNK_SIZE) return uploadChunked(file, fileName, sha256, onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("bid", bookId.toString())
            .addFormDataPart("update_meta", "0")
            .addFormDataPart("ebook", fileName, SliceRequestBody(file, 0, file.length(), onProgress))
            .build()
        return uploadResult(multipart("/api/book/upload", body))
    }

    fun uploadCover(bookId: Long, bytes: ByteArray, mime: String = "image/jpeg", current: JSONObject? = null) {
        require(bookId > 0 && bytes.isNotEmpty())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("cover_data", "cover.jpg", bytes.toRequestBody(mime.toMediaType()))
            .apply {
                if (current != null) {
                    addFormDataPart("expected_uuid", current.getString("uuid"))
                    addFormDataPart("expected_cover_version", current.getString("cover_version"))
                }
            }
            .build()
        val data = multipart("/api/book/$bookId/cover", body)
        if (data.optString("err") != "ok") throwPoxenError(data)
    }

    private fun uploadChunked(file: File, fileName: String, sha256: String,
        onProgress: (Long, Long) -> Unit): PoxenUploadResult {
        require(file.length() > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
        val total = ((file.length() + POXEN_UPLOAD_CHUNK_SIZE - 1) / POXEN_UPLOAD_CHUNK_SIZE).toInt()
        var sent = 0L
        for (index in 0 until total) {
            val offset = index.toLong() * POXEN_UPLOAD_CHUNK_SIZE
            val length = minOf(POXEN_UPLOAD_CHUNK_SIZE.toLong(), file.length() - offset)
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("filename", fileName)
                .addFormDataPart("chunk_index", index.toString())
                .addFormDataPart("total_chunks", total.toString())
                .addFormDataPart("file_hash", sha256)
                .addFormDataPart("chunk", "chunk", SliceRequestBody(file, offset, length) { written, _ ->
                    onProgress(sent + written, file.length())
                })
                .build()
            val data = multipart("/api/book/upload/chunk", body)
            sent += length
            onProgress(sent, file.length())
            if (data.optLong("book_id", 0L) > 0L) return uploadResult(data)
            if (data.optString("err") != "ok") throwPoxenError(data)
        }
        error("服务器未完成正文上传")
    }

    private fun uploadResult(data: JSONObject): PoxenUploadResult {
        val err = data.optString("err")
        if (err !in setOf("ok", "samebook", "format.already_exists")) throwPoxenError(data)
        val bookId = data.optLong("book_id", 0L)
        require(bookId > 0L) { "服务器未返回书籍 ID" }
        val sha256 = data.optString("sha256").trim().takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        require(sha256 != null) { "服务器未返回正文校验值，请更新服务端" }
        return PoxenUploadResult(bookId, sha256)
    }

    private fun multipart(path: String, body: MultipartBody): JSONObject {
        val url = (baseUrl + path).toHttpUrl()
        val credential = PoxenSession.cookieHeader(token(), url)
        if (credential.isBlank()) throw MyBooksHttpException(401, """{"error":"登录已失效，请重新登录"}""")
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cookie", credential).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MyBooksHttpException(response.code, text)
            return JSONObject(text)
        }
    }

    private fun throwPoxenError(data: JSONObject): Nothing {
        val err = data.optString("err")
        throw MyBooksHttpException(poxenErrorStatus(err),
            JSONObject().put("error", data.optString("msg").ifBlank { err }).toString())
    }

    fun editBook(id: Long, patch: JSONObject, current: JSONObject): String {
        require(current.optLong("id") == id) { "书籍身份不匹配" }
        check(current.optBoolean("supports_guarded_edits")) { "请更新 mybooks 服务端后再同步书目编辑" }
        if (patch.has("favorite") || patch.has("folder")) {
            check(current.optBoolean("supports_organization_edits")) { "请更新 mybooks 后重试" }
        }
        val uuid = requireNotNull(current.optTextOrNull("uuid")) { "书籍缺少 UUID" }
        val metadata = JSONObject()
        for (key in patch.keys()) {
            when (key) {
                "author" -> metadata.put("authors", JSONArray(patch.getString(key).split('、').map(String::trim).filter(String::isNotEmpty)))
                "summary" -> metadata.put("comments", Entities.escape(patch.getString(key)).replace("\n", "<br/>"))
                "pub_year" -> metadata.put("pubdate", patch.getString(key))
                "series_index" -> metadata.put(key, patch.get(key).toString().ifBlank { "0" }.toDouble())
                "status", "read_logs", "favorite" -> Unit
                in poxenWritableBookFields -> metadata.put(key, patch.get(key))
                else -> error("不支持回传字段：$key")
            }
        }
        val status = if (patch.has("status") || patch.has("read_logs")) {
            check(current.optBoolean("supports_reading_choice")) { "请更新 mybooks 后再同步阅读状态" }
            val target = patch.optString("status", current.getString("status"))
            JSONObject().put("expected_uuid", uuid)
                .put("expected_read_state", nativeReadState(current.getString("status")))
                .put("read_state", nativeReadState(target))
                .put("expected_wants", current.optBoolean("server_wants"))
                .put("wants", target == "want_to_read")
                .apply {
                    if (patch.has("read_logs")) {
                        put("expected_read_logs", current.getJSONArray("read_logs"))
                        put("read_logs", patch.getJSONArray("read_logs"))
                    }
                }
        } else null
        val favorite = if (patch.has("favorite")) JSONObject().put("expected_uuid", uuid)
            .put("expected_favorite", current.optBoolean("favorite"))
            .put("favorite", patch.getBoolean("favorite")) else null
        if (metadata.length() > 0) {
            metadata.put("expected_uuid", uuid)
            metadata.put("expected_last_modified", requireNotNull(current.optTextOrNull("updated_at")) { "书籍缺少修改时间" })
        }
        try {
            if (metadata.length() > 0) post("/api/book/$id/edit", metadata)
            if (status != null) post("/api/book/$id/readstate", status)
            if (favorite != null) post("/api/book/$id/favorite", favorite)
        } catch (error: MyBooksHttpException) {
            if (error.status != 409) throw error
            val latest = JSONObject(book(id))
            throw MyBooksHttpException(409, JSONObject(error.body).put("current", latest).toString())
        }
        return book(id).also { check(JSONObject(it).optString("uuid") == uuid) { "书籍身份不匹配" } }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun nativeReadState(status: String): Int = when (status) {
        "wish", "want_to_read" -> 0
        "reading" -> 1
        "done" -> 2
        else -> error("无效的阅读状态")
    }
}

private fun poxenErrorStatus(err: String): Int = when {
    err == "book.conflict" -> 409
    err == "not_found" || err == "book.not_found" -> 404
    err.contains("login") || err.contains("user") && err.contains("invalid") -> 401
    err.contains("permission") -> 403
    err.contains("book") && err.contains("invalid") -> 404
    else -> 400
}

internal fun checkedPoxenResponse(body: String): JSONObject {
    val data = JSONObject(body)
    val err = data.optString("err")
    if (err != "ok") {
        throw MyBooksHttpException(poxenErrorStatus(err),
            JSONObject().put("error", data.optString("msg").ifBlank { err }).toString())
    }
    return data
}

private class SliceRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val onProgress: (Long, Long) -> Unit = { _, _ -> }
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length

    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) error("正文读取失败")
                sink.write(buffer, 0, read)
                remaining -= read
                onProgress(length - remaining, length)
            }
        }
    }
}

/** Convert upstream fields at the boundary; reader/database/UI keep their existing model. */
internal fun normalizePoxenBook(book: JSONObject): JSONObject {
    val id = book.getLong("id")
    require(id > 0 && book.optString("title").isNotBlank()) { "书籍数据格式不正确" }
    val authors = book.optJSONArray("authors")
    val author = if (authors != null) (0 until authors.length()).map { authors.getString(it) }.joinToString("、")
        else book.optString("author")
    val state = book.optJSONObject("state")
    val favorite = state?.optBoolean("favorite") == true || state?.optInt("favorite") == 1
    val wants = state?.optBoolean("wants") == true || state?.optInt("wants") == 1
    val status = when (state?.optInt("read_state", 0)) {
        1 -> "reading"
        2 -> "done"
        else -> if (wants) "want_to_read" else "wish"
    }
    val files = JSONArray()
    book.optJSONArray("files")?.let { rows ->
        for (index in 0 until rows.length()) {
            val file = rows.getJSONObject(index)
            val href = file.optString("href")
            if (href.isNotBlank()) files.put(JSONObject().put("format", file.getString("format"))
                .put("path", href).put("download_url", href).put("size", file.optLong("size"))
                .put("file_version", file.optTextOrNull("file_version")))
        }
    }
    return JSONObject().put("id", id).put("title", book.getString("title")).put("author", author)
        .put("translators", book.optJSONArray("translators") ?: JSONArray())
        .put("summary", Jsoup.parse(book.optString("comments")).wholeText())
        .put("cover", book.optString("img").let { url ->
            val version = book.optTextOrNull("cover_version")
            if (url.isBlank() || version == null) url else url + (if ('?' in url) "&" else "?") + "v=" + version
        }).put("status", status)
        .put("favorite", favorite).put("server_wants", wants)
        .put("category", book.optTextOrEmpty("category").trim()).put("folder", book.optTextOrEmpty("folder").trim())
        .put("tags", book.optJSONArray("tags") ?: JSONArray())
        .put("rating", book.optDouble("rating", 0.0)).put("publisher", book.optString("publisher"))
        .put("series", book.optString("series")).put("series_index", book.opt("series_index"))
        .put("series_label", book.optString("series")).put("isbn", book.optString("isbn"))
        .put("pub_year", book.optString("pubdate").take(4).takeUnless { it == "0101" }.orEmpty()).put("files", files)
        .apply {
            if (book.has("cover_version")) put("cover_version", book.getString("cover_version"))
            book.optJSONArray("read_logs")?.let { put("read_logs", it) }
            if (book.optBoolean("supports_guarded_edits")) put("supports_guarded_edits", true)
            if (book.optBoolean("supports_guarded_delete")) put("supports_guarded_delete", true)
            if (book.optBoolean("supports_reading_choice")) put("supports_reading_choice", true)
            if (book.optBoolean("supports_organization_edits")) put("supports_organization_edits", true)
            book.optString("uuid").takeIf { it.isNotBlank() && it != "null" }?.let { put("uuid", it) }
            book.optString("last_modified").takeIf { it.isNotBlank() && it != "null" }?.let { put("updated_at", it) }
        }
}
