package com.huangder.lumibooks.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.*
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.util.BookFileAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MyBooksTransferProgress(val title: String, val index: Int, val total: Int, val bytes: Long = 0, val size: Long = 0)
data class MyBooksUploadResult(val bookIds: Set<String>, val errors: List<String>)

internal fun canUploadMyBooksBook(book: Book): Boolean = MyBooksConfig.isMyBooks(book) ||
    (!book.isCloudOnly && !book.isMissing && book.filePath.isNotBlank() && book.remoteLibraryKey.isNullOrBlank())

@Singleton
class MyBooksBookTransfer @Inject constructor(
    private val db: AppDatabase,
    private val manager: MyBooksSyncManager,
    @ApplicationContext private val context: Context
) {
    private val api = MyBooksApiClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val books = db.bookDao().getAllBooks()

    suspend fun upload(bookIds: Set<String>, progress: (MyBooksTransferProgress) -> Unit): MyBooksUploadResult = scope.async {
        val result = uploadTransfer(bookIds, progress)
        if (result.bookIds.isNotEmpty()) {
            val synced = manager.syncNow(scheduleRetry = false, direction = MyBooksSyncDirection.UPLOAD, selectedBookIds = result.bookIds)
            if (!synced.success) return@async result.copy(errors = result.errors + synced.message)
        }
        result
    }.await()

    private suspend fun uploadTransfer(bookIds: Set<String>, progress: (MyBooksTransferProgress) -> Unit): MyBooksUploadResult =
        withContext(Dispatchers.IO) { manager.withBookTransfer { performUpload(bookIds, progress) } }

    internal suspend fun uploadUnderSyncLock(bookIds: Set<String>): MyBooksUploadResult = withContext(Dispatchers.IO) { performUpload(bookIds) { } }

    private suspend fun performUpload(bookIds: Set<String>, progress: (MyBooksTransferProgress) -> Unit): MyBooksUploadResult {
        db.repairDanglingBookRelations()
        if (MyBooksConfig.isPoxenServer()) return performNativeUpload(bookIds, progress)
        val snapshot = api.fetchSyncSnapshot()
        check(snapshot.syncVersion >= 3) { "请先升级服务器以支持经过正文校验的书籍上传" }
        val library = requireNotNull(snapshot.libraryId) { "请先升级服务器再上传书籍" }
        val known = db.syncStateDao().getValue(MyBooksSyncManager.KEY_LIBRARY_ID)
        check(known.isNullOrBlank() || known == library) { "书库身份已变化，请检查服务器配置" }
        db.syncStateDao().putState(SyncStateEntity(MyBooksSyncManager.KEY_LIBRARY_ID, library))
        val uploaded = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        for ((index, id) in bookIds.withIndex()) {
            currentCoroutineContext().ensureActive()
            val book = db.bookDao().getBookById(id) ?: continue
            try {
                if (id.startsWith(MyBooksConfig.BOOK_ID_PREFIX)) { uploaded += id; continue }
                check(book.remoteLibraryKey.isNullOrBlank() && !book.isCloudOnly && !book.isMissing) { "请先将书籍下载到本机" }
                uploaded += uploadOne(id, library) { bytes, size -> progress(MyBooksTransferProgress(book.title, index + 1, bookIds.size, bytes, size)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val message = error.message.orEmpty()
                db.syncStateDao().putState(SyncStateEntity("mybooks_upload_error:" + library + ":" + id,
                    JSONObject().put("bookId", id).put("message", message).toString()))
                errors += book.title + ": " + message
                if (classifyMyBooksSyncFailure(error) in setOf(MyBooksSyncFailure.AUTH, MyBooksSyncFailure.TRANSIENT)) break
            }
        }
        return MyBooksUploadResult(uploaded, errors)
    }

    private suspend fun performNativeUpload(bookIds: Set<String>, progress: (MyBooksTransferProgress) -> Unit): MyBooksUploadResult {
        val library = MyBooksConfig.LIBRARY_KEY
        val uploaded = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        for ((index, id) in bookIds.withIndex()) {
            currentCoroutineContext().ensureActive()
            val book = db.bookDao().getBookById(id) ?: continue
            if (book.format == "MARKDOWN") continue
            try {
                if (id.startsWith(MyBooksConfig.BOOK_ID_PREFIX)) { uploaded += id; continue }
                check(book.remoteLibraryKey.isNullOrBlank() && !book.isCloudOnly && !book.isMissing) { "请先将书籍下载到本机" }
                uploaded += uploadOneNative(id, library) { bytes, size ->
                    progress(MyBooksTransferProgress(book.title, index + 1, bookIds.size, bytes, size))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                db.syncStateDao().putState(SyncStateEntity("mybooks_upload_error:" + library + ":" + id,
                    JSONObject().put("bookId", id).put("message", message).toString()))
                errors += book.title + ": " + message
                if (classifyMyBooksSyncFailure(error) in setOf(MyBooksSyncFailure.AUTH, MyBooksSyncFailure.TRANSIENT)) break
            }
        }
        return MyBooksUploadResult(uploaded, errors)
    }

    internal suspend fun uploadOneNative(localId: String, library: String, progress: (Long, Long) -> Unit): String {
        val book = requireNotNull(db.bookDao().getBookById(localId))
        check(book.format != "MARKDOWN") { "服务器不支持 Markdown 正文上传" }
        val extension = book.format.lowercase()
        val cache = File.createTempFile("mybooks-upload-", "." + extension, context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            BookFileAccess.openInputStream(context, book.filePath).use { input -> cache.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
            } }
            check(cache.length() > 0) { "正文为空，无法上传" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val key = "mybooks_upload:" + library + ":" + localId
            val stored = db.syncStateDao().getValue(key)?.let(::JSONObject)
            val tags = db.tagDao().getTagsForBook(localId).map { it.name }
            val draftRoot = db.syncStateDao().getValue("book_import_metadata:" + localId)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val initial = draftRoot?.optJSONObject("fields")?.let { JSONObject(it.toString()) }
                ?: JSONObject().put("title", book.title).put("author", book.author).put("tags", JSONArray(tags))
            initial.put("title", book.title).put("author", book.author).put("tags", JSONArray(tags))
            val fileName = nativeUploadName(book.title, extension)

            var transfer = stored
            var raw: String? = null
            if (transfer != null) {
                check(transfer.optString("sha256").ifBlank { hash } == hash) {
                    "本机正文已变化，已停止上传"
                }
                val remoteId = transfer.getLong("id")
                try {
                    raw = api.fetchBookJson(remoteId)
                } catch (error: MyBooksHttpException) {
                    if (error.status == 404) {
                        db.syncStateDao().deleteState(key)
                        transfer = null
                    } else {
                        throw error
                    }
                }
            }
            if (transfer == null) {
                val uploaded = api.uploadNativeBook(cache, fileName, hash, progress)
                check(uploaded.sha256 == hash) { "服务器正文校验失败，请重试" }
                val remoteId = uploaded.bookId
                transfer = JSONObject().put("id", remoteId).put("localBookId", localId)
                    .put("initial", initial).put("size", cache.length()).put("sha256", hash)
                db.syncStateDao().putState(SyncStateEntity(key, transfer.toString()))
                raw = api.fetchBookJson(remoteId)
                val remote = parseRemoteBook(raw!!, MyBooksConfig.SERVER_URL)
                val remoteFile = remote.fileFor(extension)
                check(remoteFile != null && remoteFile.size == cache.length()) { "服务器尚未保存完整正文" }
                transfer.put("uuid", remote.uuid.orEmpty())
                db.syncStateDao().putState(SyncStateEntity(key, transfer.toString()))
            } else {
                val existing = transfer
                val remoteId = existing.getLong("id")
                var detail = parseRemoteBook(requireNotNull(raw), MyBooksConfig.SERVER_URL)
                val expectedUuid = existing.optString("uuid")
                check(expectedUuid.isBlank() || expectedUuid == detail.uuid) { "服务器书目身份已变化，已停止上传" }
                if (expectedUuid.isBlank() && !detail.uuid.isNullOrBlank()) {
                    existing.put("uuid", detail.uuid)
                    db.syncStateDao().putState(SyncStateEntity(key, existing.toString()))
                }
                var file = detail.fileFor(extension)
                if (file == null) {
                    val uploaded = api.addNativeBookFormat(remoteId, cache, fileName, hash, progress)
                    check(uploaded.bookId == remoteId) { "服务器书目已变化，已停止上传" }
                    check(uploaded.sha256 == hash) { "服务器正文校验失败，请重试" }
                    raw = api.fetchBookJson(remoteId)
                    detail = parseRemoteBook(raw!!, MyBooksConfig.SERVER_URL)
                    file = detail.fileFor(extension)
                }
                check(file != null) { "服务器尚未保存正文" }
                check(file.size == cache.length()) { "服务器已有同格式但正文大小不一致，请先处理重复书籍" }
            }

            val confirmed = requireNotNull(transfer)
            val remoteId = confirmed.getLong("id")
            var currentRaw = requireNotNull(raw)
            val remote = parseRemoteBook(currentRaw, MyBooksConfig.SERVER_URL)
            val coverFile = book.coverPath?.let(::File)?.takeIf { it.isFile }
            val doubanCover = (draftRoot?.opt("coverUrl") as? String)?.isNotBlank() == true
            val needsCover = coverFile != null && (
                com.huangder.lumibooks.util.FileUtils.isCustomCover(book.coverPath) ||
                    doubanCover ||
                    remote.coverUrl.isNullOrBlank()
                )
            if (needsCover) {
                val bytes = withContext(Dispatchers.IO) {
                    MyBooksCoverUploader(api).compressedBytes(requireNotNull(coverFile))
                }
                api.uploadNativeCover(remoteId, bytes)
                currentRaw = api.fetchBookJson(remoteId)
            }
            return adoptUploadedBook(localId, currentRaw, hash, cache.length(), initial, key)
        } finally {
            cache.delete()
        }
    }

    private fun nativeUploadName(title: String, extension: String): String {
        val base = title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "book" }
        return "$base.$extension"
    }

    private suspend fun uploadOne(localId: String, library: String, progress: (Long, Long) -> Unit): String {
        val book = requireNotNull(db.bookDao().getBookById(localId))
        val extension = if (book.format == "MARKDOWN") "md" else book.format.lowercase()
        val cache = File.createTempFile("mybooks-upload-", "." + extension, context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            BookFileAccess.openInputStream(context, book.filePath).use { input -> cache.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); digest.update(buffer, 0, n) }
            } }
            check(cache.length() > 0) { "正文为空，无法上传" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val key = "mybooks_upload:" + library + ":" + localId
            val stored = db.syncStateDao().getValue(key)?.let(::JSONObject)
            val tags = db.tagDao().getTagsForBook(localId).map { it.name }
            val draft = db.syncStateDao().getValue("book_import_metadata:" + localId)?.let(::JSONObject)
            val initial = stored?.getJSONObject("initial") ?: (draft?.optJSONObject("fields")?.let { JSONObject(it.toString()) }
                ?: JSONObject().put("title", book.title).put("author", book.author).put("tags", JSONArray(tags)))
            initial.put("title", book.title).put("author", book.author).put("tags", JSONArray(tags))
            val transfer = stored ?: run {
                val payload = JSONObject(initial.toString()).apply { if (!has("status")) put("status", if (book.readingProgress >= 1f) "read" else "wish") }
                book.coverPath?.let(::File)?.takeIf { it.isFile }?.let { payload.put("cover", MyBooksCoverUploader(api).upload(it, MyBooksConfig.adminToken)) }
                val created = api.createBook(payload, MyBooksConfig.adminToken)
                JSONObject().put("id", created.id).put("uuid", requireNotNull(created.uuid)).put("revision", requireNotNull(created.revision))
                    .put("localBookId", localId).put("initial", initial).also { db.syncStateDao().putState(SyncStateEntity(key, it.toString())) }
            }
            val remoteId = transfer.getLong("id")
            var raw = api.fetchBookJson(remoteId)
            var remote = parseRemoteBook(raw, MyBooksConfig.SERVER_URL)
            check(remote.uuid == transfer.getString("uuid")) { "服务器书目身份已变化，已停止上传" }
            val file = remote.fileFor(extension) ?: remote.fileFor(book.format)
            val alreadyUploaded = file?.downloadUrl?.let { api.fileSha256(it) } == hash
            if (!alreadyUploaded) {
                check(file == null && remote.revision == transfer.getLong("revision")) { "服务器书目已修改，已停止覆盖正文" }
                val coroutine = currentCoroutineContext()
                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = cache.length()
                    override fun writeTo(sink: BufferedSink) {
                        var written = 0L
                        cache.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
                            coroutine.ensureActive(); val n = input.read(buffer); if (n < 0) break
                            sink.write(buffer, 0, n); written += n; progress(written, cache.length())
                        } }
                    }
                }
                api.uploadBookFile(remoteId, "book." + extension, body, MyBooksConfig.adminToken)
                raw = api.fetchBookJson(remoteId); remote = parseRemoteBook(raw, MyBooksConfig.SERVER_URL)
            }
            check(remote.uuid == transfer.getString("uuid")) { "服务器书目身份已变化" }
            val published = requireNotNull(remote.fileFor(extension) ?: remote.fileFor(book.format)) { "服务器尚未保存正文" }
            check(published.downloadUrl?.let { api.fileSha256(it) } == hash) { "上传后的正文校验失败，请重试" }
            return adoptUploadedBook(localId, raw, hash, cache.length(), initial, key)
        } finally { cache.delete() }
    }

    internal suspend fun adoptUploadedBook(localId: String, raw: String, hash: String, size: Long, initial: JSONObject, pendingKey: String): String {
        val target = MyBooksConfig.BOOK_ID_PREFIX + parseRemoteBook(raw, MyBooksConfig.SERVER_URL).id
        check(db.bookDao().getBookById(target) == null) { "服务器书目已有本机副本，请先处理重复书籍" }
        com.huangder.lumibooks.data.local.copyBookScopedPreferences(context, localId, target)
        return db.withTransaction {
        val old = requireNotNull(db.bookDao().getBookById(localId)) { "本机书目已移除，服务器副本仍保留" }
        val remote = parseRemoteBook(raw, MyBooksConfig.SERVER_URL)
        val targetId = MyBooksConfig.BOOK_ID_PREFIX + remote.id
        check(db.bookDao().getBookById(targetId) == null) { "服务器书目已有本机副本，请先处理重复书籍" }
        val native = MyBooksConfig.isPoxenServer()
        val titleChanged = old.title != initial.optString("title")
        val authorChanged = old.author != initial.optString("author")
        val overrides = if (native) nativeInitialOverrides(old, initial) else JSONObject().apply {
            if (titleChanged) put("title", old.title)
            if (authorChanged) put("author", old.author)
        }
        db.bookDao().insertBook(old.copy(id = targetId,
            title = if (native || titleChanged) old.title else remote.title,
            author = if (native || authorChanged) old.author else remote.author,
            remoteLibraryKey = MyBooksConfig.LIBRARY_KEY,
            remoteFileSize = size, remoteFileSha256 = hash, sourceSha256 = hash))
        // Insert the target first, move every book relation, then remove the source. Foreign keys remain valid throughout.
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (table in tables) {
            val quoted = "\"" + table.replace("\"", "\"\"") + "\""
            val hasBookId = sql.query("PRAGMA table_info(" + quoted + ")").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name"); var found = false
                while (cursor.moveToNext()) if (cursor.getString(name) == "bookId") found = true
                found
            }
            if (hasBookId) sql.execSQL("UPDATE " + quoted + " SET bookId=? WHERE bookId=?", arrayOf(targetId, localId))
        }
        val localTags = db.tagDao().getTagsForBook(targetId).map { it.name }.toSet()
        val initialTags = (initial.optJSONArray("tags") ?: JSONArray())
            .let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        if ((native && localTags.isEmpty() && initialTags.isEmpty()) || (!native && localTags == initialTags)) {
            db.tagDao().getBookTagLinks(targetId).forEach { db.tagDao().deleteBookTagLink(targetId, it.tagId) }
            remote.tags.forEach { tag -> db.tagDao().createAndAssignTag(targetId, TagEntity(UUID.randomUUID().toString(), tag, tag.lowercase(), System.currentTimeMillis())) }
        }
        val previews = sql.query("SELECT id,previewBookIds FROM folders WHERE previewBookIds IS NOT NULL").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        for ((folder, preview) in previews) {
            val ids = runCatching { JSONArray(preview) }.getOrNull() ?: continue
            var changed = false
            for (index in 0 until ids.length()) if (ids.optString(index) == localId) { ids.put(index, targetId); changed = true }
            if (changed) db.folderDao().updateFolderPreviewIfChanged(folder, ids.toString())
        }
        db.bookDao().deleteBookById(localId)
        val edits = JSONObject(); overrides.keys().forEach { edits.put(it, System.currentTimeMillis()) }
        db.myBooksSyncStateDao().upsert(MyBooksSyncStateEntity(targetId, remote.id, remote.updatedAt.orEmpty(), raw,
            overrides.toString(), edits.toString(), JSONArray(remote.tags).toString(), lastSyncAt = System.currentTimeMillis()))
        db.syncStateDao().deleteState(pendingKey)
        db.syncStateDao().deleteState("book_import_metadata:" + localId)
        db.syncStateDao().deleteState(pendingKey.replaceFirst("mybooks_upload:", "mybooks_upload_error:"))
        targetId
    }
    }

    private fun nativeInitialOverrides(old: BookEntity, initial: JSONObject): JSONObject {
        val overrides = JSONObject()
        if (old.title.isNotBlank()) overrides.put("title", old.title)
        if (old.author.isNotBlank() && old.author != UNKNOWN_REMOTE_AUTHOR) overrides.put("author", old.author)
        val translators = initial.optJSONArray("translators")
        if (translators != null && translators.length() > 0) overrides.put("translators", translators)
        fun text(key: String) {
            val value = initial.optString(key).trim()
            if (value.isNotEmpty()) overrides.put(key, value)
        }
        listOf("summary", "publisher", "isbn").forEach(::text)
        val series = initial.optString("series").trim()
        if (series.isNotEmpty()) overrides.put("series", series)
        val seriesIndex = initial.optString("series_index").trim()
        if (seriesIndex.isNotEmpty() && seriesIndex != "0") overrides.put("series_index", seriesIndex)
        val pubYear = initial.optString("pub_year").trim()
        if (pubYear.isNotEmpty()) overrides.put("pub_year", pubYear)
        val rating = initial.optDouble("rating", 0.0)
        if (rating > 0) overrides.put("rating", rating)
        val status = when {
            old.readingProgress >= 1f -> "done"
            old.readingProgress > 0f -> "reading"
            else -> initial.optString("status").trim()
        }
        if (status.isNotBlank() && status != "wish") overrides.put("status", status)
        return overrides
    }
}
