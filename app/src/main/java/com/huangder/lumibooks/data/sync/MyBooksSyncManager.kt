package com.huangder.lumibooks.data.sync

import android.content.Context
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.local.dao.MyBooksSyncStateDao
import com.huangder.lumibooks.data.local.dao.SyncStateDao
import com.huangder.lumibooks.data.local.dao.TagDao
import com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity
import com.huangder.lumibooks.data.local.entity.MyBooksPendingDeleteEntity
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import com.huangder.lumibooks.data.local.entity.TagEntity
import com.huangder.lumibooks.data.sync.BookDownloadState
import com.huangder.lumibooks.data.sync.CloudBookDownloadResult
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.TagNameValidator
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.util.FileUtils
import com.huangder.lumibooks.util.parser.BookParserFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MyBooksSyncResult(
    val message: String,
    val success: Boolean
)

/**
 * 本地书名/作者是否算作"用户改过"、需要在基线里记为本地编辑。
 *
 * 空值不算：导入正文副本时书名/作者可能还是空的，那是流程的临时状态而非用户意图，
 * 记成本地编辑会在下一轮同步把空值推回服务器。
 */
internal fun isLocalTitleAuthorEdit(local: String, remote: String): Boolean =
    local.isNotBlank() && local != remote

/**
 * my-books 双向同步:
 * 服务器快照(base) ↔ 本地 overrides ↔ 服务器当前值(remote)。
 * 阅读页只读 MyBooksNotesStore 的本地副本;网络只在同步/推送时发生。
 */
@Singleton
class MyBooksSyncManager @Inject constructor(
    private val bookRepository: BookRepository,
    private val tagDao: TagDao,
    private val syncStateDao: SyncStateDao,
    private val myBooksSyncStateDao: MyBooksSyncStateDao,
    @ApplicationContext private val context: Context
) {
    private val client = MyBooksApiClient()
    private val coverUploader = MyBooksCoverUploader(client)
    private val metaCache = MyBooksMetaCache(context)
    private val notesStore = MyBooksNotesStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val syncMutex = Mutex()
    private var pushJob: Job? = null

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, BookDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, BookDownloadState>> = _downloadStates.asStateFlow()

    suspend fun syncIfStale(maxAgeMs: Long = 10 * 60_000) {
        val now = System.currentTimeMillis()
        val last = withContext(Dispatchers.IO) {
            syncStateDao.getValue(KEY_LAST_SYNC)?.toLongOrNull() ?: 0L
        }
        if (now - last < maxAgeMs) return
        syncNow()
    }

    suspend fun syncNow(): MyBooksSyncResult = syncMutex.withLock {
        _isSyncing.value = true
        try {
            withContext(Dispatchers.IO) { runSyncNow() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MyBooksSyncResult(error.message ?: context.getString(R.string.mybooks_connection_failed), false)
        } finally {
            _isSyncing.value = false
        }
    }

    /** 本地删除前调用:把服务器软删除放进队列,随后由同步器推送。 */
    suspend fun queuePendingDelete(bookId: String): Boolean {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return false
        myBooksSyncStateDao.upsertPendingDelete(
            MyBooksPendingDeleteEntity(
                remoteId = remoteId,
                bookId = bookId,
                deletedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    fun enqueuePendingDelete(bookId: String) {
        scope.launch {
            queuePendingDelete(bookId)
            syncNow()
        }
    }

    /** 编辑页/标签操作后记录本地 field overrides,并 debounce 推送。 */
    suspend fun recordLocalEdit(
        bookId: String,
        fields: Map<String, Any?>,
        editedAt: Long = System.currentTimeMillis()
    ) {
        if (fields.isEmpty()) return
        val state = myBooksSyncStateDao.get(bookId) ?: return
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val edits = runCatching { JSONObject(state.editedAtJson) }.getOrDefault(JSONObject())
        fields.forEach { (key, value) ->
            overrides.put(key, value ?: "")
            edits.put(key, editedAt)
        }
        myBooksSyncStateDao.upsert(
            state.copy(
                overridesJson = overrides.toString(),
                editedAtJson = edits.toString(),
                lastError = null
            )
        )
        schedulePush()
    }

    /**
     * 快捷“标记已读/取消已读”同步到服务器：
     * 标记时补一条读完记录，取消时清掉最后一条 finish；两者与 status 一起进入现有合并通道。
     */
    suspend fun setReadStatus(bookId: String, finished: Boolean) {
        val state = myBooksSyncStateDao.get(bookId) ?: return
        val base = runCatching { JSONObject(state.baseJson) }.getOrDefault(JSONObject())
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val currentLogs = MyBooksReadStatus.parseLogs(
            if (overrides.has("read_logs")) overrides.opt("read_logs") else base.opt("read_logs")
        )
        val toggle = MyBooksReadStatus.toggle(
            currentLogs = currentLogs,
            markRead = finished,
            today = LocalDate.now().toString()
        )
        val logsChanged = MyBooksReadStatus.canonical(toggle.logs) !=
            MyBooksReadStatus.canonical(currentLogs)
        val currentStatus = (if (overrides.has("status")) {
            overrides.optString("status")
        } else {
            base.optString("status")
        }).ifBlank { "wish" }
        if (toggle.status == currentStatus && !logsChanged) return

        val fields = linkedMapOf<String, Any?>("status" to toggle.status)
        if (logsChanged) fields["read_logs"] = MyBooksReadStatus.logsToJson(toggle.logs)
        recordLocalEdit(bookId, fields)
    }

    fun recordCoverChange(bookId: String, editedAt: Long = System.currentTimeMillis()) {
        scope.launch {
            val state = myBooksSyncStateDao.get(bookId) ?: return@launch
            myBooksSyncStateDao.upsert(
                state.copy(coverDirty = true, coverEditedAt = editedAt, lastError = null)
            )
            schedulePush()
        }
    }

    /**
     * 编辑 Sheet 保存:一次写入全部受管字段的 override。
     * [managedKeys] 中未出现在 [changed] 的键会被移除,避免已还原的字段继续推送。
     */
    suspend fun replaceLocalEdits(
        bookId: String,
        changed: Map<String, Any?>,
        managedKeys: Set<String>,
        editedAt: Long = System.currentTimeMillis()
    ) {
        val state = myBooksSyncStateDao.get(bookId) ?: return
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        val edits = runCatching { JSONObject(state.editedAtJson) }.getOrDefault(JSONObject())
        managedKeys.forEach { key ->
            if (!changed.containsKey(key)) {
                overrides.remove(key)
                edits.remove(key)
            }
        }
        changed.forEach { (key, value) ->
            overrides.put(key, value ?: "")
            edits.put(key, editedAt)
        }
        myBooksSyncStateDao.upsert(
            state.copy(
                overridesJson = overrides.toString(),
                editedAtJson = edits.toString(),
                lastError = null
            )
        )
        schedulePush()
    }

    fun schedulePush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            syncNow()
        }
    }

    suspend fun localNote(bookId: String): MyBooksLocalNote? {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return null
        return withContext(Dispatchers.IO) { notesStore.read(remoteId) }
    }

    /** 详情页“同步并阅读”:下载服务器 .md 到本地,成功后返回本地内容。 */
    suspend fun syncNoteToLocal(bookId: String): MyBooksLocalNote? {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return null
        val payload = withContext(Dispatchers.IO) { client.fetchNotes(remoteId) }
        if (!payload.hasNotes) return withContext(Dispatchers.IO) { notesStore.read(remoteId) }
        withContext(Dispatchers.IO) {
            notesStore.write(remoteId, payload.content, payload.updatedAt, payload.size)
            notesStore.read(remoteId)
        }
        return withContext(Dispatchers.IO) { notesStore.read(remoteId) }
    }

    suspend fun saveLocalNoteAfterUpload(bookId: String, content: String, payload: MyBooksNotesPayload) {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return
        withContext(Dispatchers.IO) {
            notesStore.write(remoteId, content, payload.updatedAt, payload.size)
        }
    }

    /** 移除自定义封面后,把服务器封面重新缓存到本地并清除 coverDirty。 */
    suspend fun restoreServerCover(bookId: String): Boolean {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return false
        return runCatching {
            val json = withContext(Dispatchers.IO) { client.fetchBookJson(remoteId) }
            val detail = parseRemoteBook(json, MyBooksConfig.SERVER_URL)
            val coverPath = cacheCover(remoteId, detail.coverUrl, force = true)
            val book = bookRepository.getBookById(bookId) ?: return@runCatching false
            bookRepository.updateBookMetadata(book.copy(coverPath = coverPath ?: book.coverPath))
            myBooksSyncStateDao.get(bookId)?.let { state ->
                myBooksSyncStateDao.upsert(state.copy(coverDirty = false, coverEditedAt = 0L))
            }
            true
        }.getOrDefault(false)
    }

    /** 本地删书后清理同步状态/详情缓存/心得副本;待处理删除记录由同步器保留。 */
    suspend fun clearLocalBookData(bookId: String) {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: return
        withContext(Dispatchers.IO) {
            myBooksSyncStateDao.delete(bookId)
            metaCache.delete(remoteId)
            notesStore.delete(remoteId)
        }
    }

    /** 上传/替换 .md;成功后立即写入本地阅读副本。409 抛 [MyBooksHttpException]。 */
    suspend fun uploadNote(
        bookId: String,
        content: String,
        expectedUpdatedAt: String?
    ): MyBooksNotesPayload {
        val remoteId = MyBooksConfig.remoteIdOf(bookId) ?: error("不是服务器书籍")
        val token = MyBooksConfig.adminToken
        require(token.isNotBlank()) { context.getString(R.string.mybooks_readonly) }
        val payload = withContext(Dispatchers.IO) {
            client.saveNotes(remoteId, content, expectedUpdatedAt, token)
        }
        saveLocalNoteAfterUpload(bookId, content, payload)
        return payload
    }

    // ────────────────────────── core sync ──────────────────────────

    private suspend fun runSyncNow(): MyBooksSyncResult {
        val token = MyBooksConfig.adminToken
        pushPendingDeletes(token)

        val remoteBooks = try {
            client.fetchBooks()
        } catch (error: Exception) {
            return MyBooksSyncResult(
                error.message ?: context.getString(R.string.mybooks_connection_failed),
                false
            )
        }

        val localBefore = bookRepository.getAllBooks().first().filter(MyBooksConfig::isMyBooks)
        if (remoteBooks.isEmpty() && localBefore.isNotEmpty()) {
            return MyBooksSyncResult(
                context.getString(R.string.mybooks_empty_library_skip_delete),
                true
            )
        }

        var added = 0
        var updated = 0
        var skipped = 0
        for (remote in remoteBooks) {
            if (myBooksSyncStateDao.getPendingDelete(remote.id) != null) continue
            try {
                when (syncOneBook(remote, token)) {
                    1 -> added++
                    2 -> updated++
                    else -> skipped++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                skipped++
                val bookId = MyBooksConfig.BOOK_ID_PREFIX + remote.id
                runCatching {
                    myBooksSyncStateDao.get(bookId)?.let { failedState ->
                        myBooksSyncStateDao.upsert(
                            failedState.copy(
                                lastError = error.message
                                    ?: context.getString(com.huangder.lumibooks.R.string.mybooks_sync_failed)
                            )
                        )
                    }
                }
            }
        }

        val remoteIds = remoteBooks.mapTo(mutableSetOf()) { it.id }
        val removed = pruneRemovedBooks(remoteIds)
        val now = System.currentTimeMillis()
        syncStateDao.putState(SyncStateEntity(KEY_LAST_SYNC, now.toString()))
        return MyBooksSyncResult(
            context.getString(R.string.mybooks_sync_summary, added, updated, removed),
            true
        )
    }

    private suspend fun syncOneBook(remote: RemoteBook, token: String): Int {
        val bookId = MyBooksConfig.BOOK_ID_PREFIX + remote.id
        val existing = bookRepository.getBookById(bookId)
        var state = myBooksSyncStateDao.get(bookId)
        var localTags = existing?.let { tagDao.getTagsForBook(bookId).map(TagEntity::name) }.orEmpty()

        if (existing == null) {
            val detailJson = client.fetchBookJson(remote.id)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
            val now = System.currentTimeMillis()
            val file = RemoteBookFormats.preferredFile(detail.files)
            val format = file?.format?.toBookFormatOrNull() ?: BookFormat.EPUB
            val coverPath = cacheCover(remote.id, detail.coverUrl, force = true)
            bookRepository.insertBook(
                Book(
                    id = bookId,
                    title = detail.title,
                    author = detail.author,
                    filePath = "",
                    coverPath = coverPath,
                    format = format,
                    lastReadTime = now,
                    readingProgress = 0f,
                    createdAt = now,
                    isCloudOnly = true,
                    remoteLibraryKey = MyBooksConfig.LIBRARY_KEY,
                    remoteFileName = null,
                    remoteFileSize = file?.size ?: 0L,
                    metadataUpdatedAt = parseEpoch(detail.updatedAt ?: remote.updatedAt) ?: now,
                    sourceUri = MyBooksConfig.SOURCE_URI_PREFIX + remote.id
                )
            )
            val created = bookRepository.getBookById(bookId) ?: return 0
            val newState = MyBooksSyncStateEntity(
                bookId = bookId,
                remoteId = remote.id,
                serverUpdatedAt = detail.updatedAt ?: remote.updatedAt.orEmpty(),
                baseJson = detailJson,
                baseTagsJson = JSONArray(detail.tags).toString(),
                lastSyncAt = now
            )
            myBooksSyncStateDao.upsert(newState)
            applyTags(bookId, detail.tags)
            syncNoteForDetail(detail)
            return 1
        }

        if (state == null) {
            val detailJson = client.fetchBookJson(remote.id)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
            state = createBaselineState(existing, detailJson, detail, remote.updatedAt)
            myBooksSyncStateDao.upsert(state)
            // 新建服务器书后本地副本还没有标签；先以服务器为准补齐，
            // 否则三方合并会把服务器标签误判成“本地删除”并清空。
            if (localTags.isEmpty() && detail.tags.isNotEmpty()) {
                applyTags(bookId, detail.tags)
                localTags = detail.tags
            }
        }
        val currentState = state ?: return 0

        val remoteUnchanged = !remote.updatedAt.isNullOrBlank() &&
            remote.updatedAt == currentState.serverUpdatedAt
        val pending = hasPendingChanges(currentState, localTags)
        if (remoteUnchanged && !pending) return 0

        val detailJson = client.fetchBookJson(remote.id)
        val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
        val remoteUpdatedAt = detail.updatedAt ?: remote.updatedAt ?: currentState.serverUpdatedAt
        val merge = mergeState(existing, currentState, localTags, detailJson)

        if (token.isBlank()) {
            applyRemote(
                existing,
                detail,
                merge.localWonFields,
                merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE
            )
            applyTags(bookId, merge.mergedTags)
            val updatedState = stateAfterRemoteOnly(currentState, detailJson, remoteUpdatedAt, detail.tags, merge)
            myBooksSyncStateDao.upsert(updatedState)
            syncNoteForDetail(detail)
            return 2
        }

        return pushAndApply(existing, currentState, detailJson, detail, remoteUpdatedAt, localTags, token)
    }

    private suspend fun pushAndApply(
        existing: Book,
        state: MyBooksSyncStateEntity,
        detailJson: String,
        detail: RemoteBook,
        remoteUpdatedAt: String,
        localTags: List<String>,
        token: String
    ): Int {
        var remoteJson = detailJson
        var remoteDetail = detail
        var expected = state.serverUpdatedAt
        var merge = mergeState(existing, state, localTags, remoteJson)
        var uploadedCoverUrl: String? = null

        if (merge.coverAction == MyBooksCoverAction.UPLOAD_LOCAL) {
            val coverFile = existing.coverPath?.let(::File)
            if (coverFile != null && coverFile.isFile) {
                uploadedCoverUrl = runCatching { coverUploader.upload(coverFile, token) }.getOrNull()
            }
        }

        repeat(2) { attempt ->
            val patch = buildPatch(merge, uploadedCoverUrl)
            if (patch.length() == 0) {
                applyRemote(
                    existing,
                    remoteDetail,
                    merge.localWonFields,
                    merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE
                )
                applyTags(existing.id, merge.mergedTags)
                myBooksSyncStateDao.upsert(
                    stateAfterRemoteOnly(state, remoteJson, remoteUpdatedAt, remoteDetail.tags, merge)
                )
                syncNoteForDetail(remoteDetail)
                return 2
            }
            if (expected.isNotBlank()) patch.put("expected_updated_at", expected)
            try {
                val response = client.patchBook(state.remoteId, patch, token)
                val responseDetail = parseRemoteBook(response, MyBooksConfig.SERVER_URL)
                applyRemote(
                    existing,
                    responseDetail,
                    emptySet(),
                    downloadCover = false
                )
                applyTags(existing.id, responseDetail.tags)
                val next = stateAfterPush(
                    state = state,
                    patch = patch,
                    dropOverrides = merge.dropOverrides,
                    responseJson = response,
                    responseDetail = responseDetail,
                    clearCoverDirty = (merge.coverAction == MyBooksCoverAction.UPLOAD_LOCAL && uploadedCoverUrl != null) ||
                        merge.coverAction == MyBooksCoverAction.DOWNLOAD_REMOTE
                )
                myBooksSyncStateDao.upsert(next)
                syncNoteForDetail(responseDetail)
                return 2
            } catch (error: MyBooksHttpException) {
                if (error.status != 409 || attempt == 1) throw error
                val current = JSONObject(error.body).optJSONObject("current")
                    ?: throw error
                remoteJson = current.toString()
                remoteDetail = parseRemoteBook(remoteJson, MyBooksConfig.SERVER_URL)
                expected = current.optTextOrEmpty("updated_at")
                merge = mergeState(existing, state, localTags, remoteJson)
                if (merge.coverAction != MyBooksCoverAction.UPLOAD_LOCAL) {
                    uploadedCoverUrl = null
                }
                if (merge.coverAction == MyBooksCoverAction.UPLOAD_LOCAL && uploadedCoverUrl == null) {
                    val coverFile = existing.coverPath?.let(::File)
                    if (coverFile != null && coverFile.isFile) {
                        uploadedCoverUrl = runCatching { coverUploader.upload(coverFile, token) }.getOrNull()
                    }
                }
            }
        }
        return 2
    }

    private fun buildPatch(merge: MyBooksMergeResult, uploadedCoverUrl: String?): JSONObject {
        val patch = JSONObject(merge.patch.toString())
        if (merge.tagsDirty) patch.put("tags", JSONArray(merge.mergedTags))
        if (uploadedCoverUrl != null) patch.put("cover", uploadedCoverUrl)
        return patch
    }

    private fun mergeState(
        existing: Book,
        state: MyBooksSyncStateEntity,
        localTags: List<String>,
        remoteJson: String
    ): MyBooksMergeResult = MyBooksMerge.merge(
        book = existing,
        baseJson = state.baseJson,
        remoteJson = remoteJson,
        overridesJson = state.overridesJson,
        editedAtJson = state.editedAtJson,
        baseTags = parseTags(state.baseTagsJson),
        localTags = localTags,
        coverDirty = state.coverDirty,
        coverEditedAt = state.coverEditedAt
    )

    private suspend fun createBaselineState(
        existing: Book,
        detailJson: String,
        detail: RemoteBook,
        listUpdatedAt: String?
    ): MyBooksSyncStateEntity {
        val remoteEpoch = parseEpoch(detail.updatedAt ?: listUpdatedAt) ?: 0L
        val localEpoch = existing.metadataUpdatedAt
        val localWinsBaseline = localEpoch > remoteEpoch
        val overrides = JSONObject()
        val edits = JSONObject()
        if (localWinsBaseline) {
            // 本地书名/作者与服务端不一致才记为"本地编辑"。
            // 仅靠时间戳判断会把导入正文副本时按文件名写入的临时值当成用户编辑，
            // 再推回服务器，把豆瓣填好的书名作者覆盖掉。
            if (isLocalTitleAuthorEdit(existing.title, detail.title)) {
                overrides.put("title", existing.title)
                edits.put("title", localEpoch)
            }
            if (isLocalTitleAuthorEdit(existing.author, detail.author)) {
                overrides.put("author", existing.author)
                edits.put("author", localEpoch)
            }
        }
        val customCover = FileUtils.isCustomCover(existing.coverPath)
        return MyBooksSyncStateEntity(
            bookId = existing.id,
            remoteId = detail.id,
            serverUpdatedAt = detail.updatedAt ?: listUpdatedAt.orEmpty(),
            baseJson = detailJson,
            overridesJson = overrides.toString(),
            editedAtJson = edits.toString(),
            baseTagsJson = JSONArray(detail.tags).toString(),
            coverDirty = localWinsBaseline && customCover,
            coverEditedAt = if (localWinsBaseline && customCover) localEpoch else 0L,
            lastSyncAt = System.currentTimeMillis()
        )
    }

    private suspend fun hasPendingChanges(state: MyBooksSyncStateEntity, localTags: List<String>): Boolean {
        val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
        if (overrides.length() > 0 || state.coverDirty) return true
        return !tagSetEquals(localTags, parseTags(state.baseTagsJson))
    }

    private suspend fun applyRemote(
        existing: Book,
        detail: RemoteBook,
        localWonFields: Set<String>,
        downloadCover: Boolean
    ) {
        val preferred = RemoteBookFormats.preferredFile(detail.files)
        val format = if (existing.isCloudOnly) {
            preferred?.format?.toBookFormatOrNull() ?: existing.format
        } else {
            existing.format
        }
        var coverPath = existing.coverPath
        if (downloadCover && !detail.coverUrl.isNullOrBlank()) {
            coverPath = cacheCover(detail.id, detail.coverUrl, force = true) ?: existing.coverPath
        }
        val remoteEpoch = parseEpoch(detail.updatedAt) ?: existing.metadataUpdatedAt
        val updated = existing.copy(
            title = if ("title" in localWonFields) existing.title else detail.title,
            author = if ("author" in localWonFields) existing.author else detail.author,
            coverPath = coverPath,
            format = format,
            remoteFileSize = preferred?.size ?: existing.remoteFileSize,
            metadataUpdatedAt = remoteEpoch
        )
        bookRepository.updateBook(updated)
    }

    private suspend fun stateAfterRemoteOnly(
        state: MyBooksSyncStateEntity,
        remoteJson: String,
        remoteUpdatedAt: String,
        remoteTags: List<String>,
        merge: MyBooksMergeResult
    ): MyBooksSyncStateEntity {
        val latest = myBooksSyncStateDao.get(state.bookId) ?: state
        val overrides = removeOverrides(latest.overridesJson, merge.dropOverrides)
        val edits = removeEditedAt(latest.editedAtJson, merge.dropOverrides)
        return latest.copy(
            serverUpdatedAt = remoteUpdatedAt,
            baseJson = remoteJson,
            overridesJson = overrides.toString(),
            editedAtJson = edits.toString(),
            baseTagsJson = JSONArray(remoteTags).toString(),
            coverDirty = latest.coverDirty && merge.coverAction != MyBooksCoverAction.DOWNLOAD_REMOTE,
            lastSyncAt = System.currentTimeMillis(),
            lastError = null
        )
    }

    private suspend fun stateAfterPush(
        state: MyBooksSyncStateEntity,
        patch: JSONObject,
        dropOverrides: Set<String>,
        responseJson: String,
        responseDetail: RemoteBook,
        clearCoverDirty: Boolean
    ): MyBooksSyncStateEntity {
        val pushedKeys = linkedSetOf<String>()
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                "identifiers" -> patch.optJSONObject("identifiers")?.keys()?.forEach {
                    pushedKeys += MyBooksConfig.identifierOverrideKey(it)
                }
                "custom" -> patch.optJSONObject("custom")?.keys()?.forEach {
                    pushedKeys += MyBooksConfig.customOverrideKey(it)
                }
                else -> pushedKeys += key
            }
        }
        pushedKeys += dropOverrides
        // 同步期间可能有新的本地编辑落库;以最新 overrides 为准,只移除本次已推送/丢弃的键。
        val latest = myBooksSyncStateDao.get(state.bookId) ?: state
        val overrides = removeOverrides(latest.overridesJson, pushedKeys)
        val edits = removeEditedAt(latest.editedAtJson, pushedKeys)
        return latest.copy(
            serverUpdatedAt = responseDetail.updatedAt ?: state.serverUpdatedAt,
            baseJson = responseJson,
            overridesJson = overrides.toString(),
            editedAtJson = edits.toString(),
            baseTagsJson = JSONArray(responseDetail.tags).toString(),
            coverDirty = if (clearCoverDirty) false else latest.coverDirty,
            lastSyncAt = System.currentTimeMillis(),
            lastError = null
        )
    }

    private fun removeOverrides(json: String, keys: Set<String>): JSONObject {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        keys.forEach { obj.remove(it) }
        return obj
    }

    private fun removeEditedAt(json: String, keys: Set<String>): JSONObject {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        keys.forEach { obj.remove(it) }
        return obj
    }

    private suspend fun applyTags(bookId: String, tagNames: List<String>) {
        val now = System.currentTimeMillis()
        val wanted = LinkedHashMap<String, String>()
        for (raw in tagNames) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val normalized = TagNameValidator.normalized(name)
            val existing = tagDao.getTagByNormalizedName(normalized)
            val tagId = existing?.id ?: run {
                val id = UUID.randomUUID().toString()
                val inserted = tagDao.insertTag(
                    TagEntity(
                        id = id,
                        name = name,
                        normalizedName = normalized,
                        createdAt = now,
                        parentId = null,
                        updatedAt = now
                    )
                )
                if (inserted != -1L) id else tagDao.getTagByNormalizedName(normalized)?.id
            } ?: continue
            wanted[name] = tagId
        }

        val links = wanted.values.distinct().map { tagId ->
            BookTagCrossRefEntity(bookId = bookId, tagId = tagId, updatedAt = now)
        }
        if (links.isNotEmpty()) syncStateDao.upsertBookTagLinks(links)
        val keep = wanted.values.toSet()
        tagDao.getBookTagLinks(bookId)
            .filter { it.tagId !in keep }
            .forEach { tagDao.deleteBookTagLink(bookId, it.tagId) }
    }

    private suspend fun pruneRemovedBooks(remoteIds: Set<Long>): Int {
        val localBooks = bookRepository.getAllBooks().first()
        var removed = 0
        for (book in localBooks) {
            if (!MyBooksConfig.isMyBooks(book)) continue
            val remoteId = MyBooksConfig.remoteIdOf(book.id) ?: continue
            if (remoteId in remoteIds) continue
            val deleted = runCatching {
                if (book.filePath.isNotBlank()) {
                    FileUtils.deleteAppManagedBookFile(context, book.filePath)
                }
                FileUtils.deleteCustomCover(context, book.id)
                book.coverPath?.let { path ->
                    if (File(path).name.startsWith("mybooks_cover_")) {
                        File(path).delete()
                    }
                }
                FileUtils.deleteTxtIndexCache(context, book.filePath)
                bookRepository.deleteBook(book)
                myBooksSyncStateDao.delete(book.id)
                myBooksSyncStateDao.deletePendingDelete(remoteId)
                notesStore.delete(remoteId)
                metaCache.delete(remoteId)
            }.isSuccess
            if (deleted) removed++
        }
        return removed
    }

    private suspend fun pushPendingDeletes(token: String) {
        if (token.isBlank()) return
        for (item in myBooksSyncStateDao.getPendingDeletes()) {
            try {
                client.deleteBook(item.remoteId, token)
                myBooksSyncStateDao.deletePendingDelete(item.remoteId)
            } catch (error: Exception) {
                myBooksSyncStateDao.upsertPendingDelete(
                    item.copy(
                        lastAttemptAt = System.currentTimeMillis(),
                        lastError = error.message
                    )
                )
            }
        }
    }

    private suspend fun syncNoteForDetail(detail: RemoteBook) {
        if (!detail.hasNotes) return
        fun current(): Boolean = notesStore.isCurrent(detail.id, detail.notesUpdatedAt, detail.notesSize)
        if (withContext(Dispatchers.IO) { current() }) return
        runCatching {
            val payload = client.fetchNotes(detail.id)
            if (payload.hasNotes) {
                withContext(Dispatchers.IO) {
                    notesStore.write(detail.id, payload.content, payload.updatedAt ?: detail.notesUpdatedAt, payload.size)
                }
            }
        }
    }

    private suspend fun cacheCover(remoteId: Long, coverUrl: String?, force: Boolean = false): String? {
        if (coverUrl.isNullOrBlank()) return null
        val hash = Integer.toHexString(coverUrl.hashCode())
        val covers = FileUtils.getCoversDirectory(context)
        val dest = File(covers, "mybooks_cover_${remoteId}_$hash.jpg")
        if (!force && dest.isFile && dest.length() > 0L) return dest.absolutePath
        val ok = client.fetchCover(coverUrl, dest)
        if (!ok) return dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        covers.listFiles()
            ?.filter { it.name.startsWith("mybooks_cover_${remoteId}_") && it.absolutePath != dest.absolutePath }
            ?.forEach { it.delete() }
        return dest.absolutePath
    }

    // ────────────────────────── download / notes API ──────────────────────────

    /** 下载服务器书正文到本地书库,进度写入 [downloadStates]。 */
    suspend fun downloadBook(bookId: String): CloudBookDownloadResult {
        val remoteId = MyBooksConfig.remoteIdOf(bookId)
            ?: return CloudBookDownloadResult(
                null,
                context.getString(R.string.mybooks_no_download_source),
                false
            )
        return try {
            val detailJson = client.fetchBookJson(remoteId)
            metaCache.write(remoteId, detailJson)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
            val file = RemoteBookFormats.preferredFile(detail.files)
                ?: return CloudBookDownloadResult(
                    null,
                    context.getString(R.string.mybooks_no_download_file),
                    false
                )
            val format = file.format.toBookFormatOrNull()
                ?: return CloudBookDownloadResult(
                    null,
                    context.getString(R.string.mybooks_unsupported_format, file.format),
                    false
                )
            val extension = format.extensionName()
            val booksDirectory = FileUtils.getBooksDirectory(context)
            val partial = File(booksDirectory, "$bookId.$extension.part")
            val destination = File(booksDirectory, "$bookId.$extension")
            val downloadUrl = resolveUrl(
                MyBooksConfig.SERVER_URL,
                if (file.path.startsWith("http://") || file.path.startsWith("https://")) {
                    file.path
                } else {
                    "/books-files/${file.path.removePrefix("/")}"
                }
            )
            _downloadStates.value = _downloadStates.value +
                (bookId to BookDownloadState.Downloading(0L, file.size))
            client.downloadFile(downloadUrl, partial) { bytesRead, totalBytes ->
                _downloadStates.value = _downloadStates.value +
                    (bookId to BookDownloadState.Downloading(bytesRead, totalBytes))
            }
            if (!partial.renameTo(destination)) {
                error(context.getString(R.string.mybooks_download_write_failed))
            }
            val readingFile = if (format == BookFormat.MARKDOWN) {
                com.huangder.lumibooks.util.markdown.MarkdownSnapshotStore.create(
                    context,
                    bookId,
                    destination.absolutePath
                )
            } else {
                destination
            }
            var coverPath = bookRepository.getBookById(bookId)?.coverPath
            if (coverPath.isNullOrBlank()) {
                coverPath = runCatching {
                    val parser = BookParserFactory.createParser(format, context)
                    try {
                        parser.extractCoverPath(readingFile.absolutePath)
                    } finally {
                        parser.close()
                    }
                }.getOrNull()
            }
            bookRepository.markBookDownloaded(bookId, readingFile.absolutePath)
            val updated = bookRepository.getBookById(bookId)
                ?: error(context.getString(R.string.mybooks_download_read_failed))
            _downloadStates.value = _downloadStates.value + (bookId to BookDownloadState.Completed)
            scope.launch {
                delay(1_500)
                if (_downloadStates.value[bookId] == BookDownloadState.Completed) {
                    _downloadStates.value = _downloadStates.value - bookId
                }
            }
            CloudBookDownloadResult(updated, context.getString(R.string.mybooks_download_done), true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileUtils.getBooksDirectory(context).listFiles()
                ?.filter { it.name.startsWith("$bookId.") && it.name.endsWith(".part") }
                ?.forEach { it.delete() }
            _downloadStates.value = _downloadStates.value +
                (bookId to BookDownloadState.Failed(
                    error.message ?: context.getString(R.string.mybooks_download_failed)
                ))
            CloudBookDownloadResult(
                null,
                error.message ?: context.getString(R.string.mybooks_download_failed),
                false
            )
        }
    }

    private fun parseTags(json: String): List<String> = runCatching {
        parseRemoteTags(JSONArray(json))
    }.getOrElse { emptyList() }

    private fun tagSetEquals(a: List<String>, b: List<String>): Boolean =
        a.map { it.lowercase() }.toSet() == b.map { it.lowercase() }.toSet()

    private fun parseEpoch(value: String?): Long? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }

    private fun String.toBookFormatOrNull(): BookFormat? = when (uppercase()) {
        "EPUB" -> BookFormat.EPUB
        "PDF" -> BookFormat.PDF
        "TXT" -> BookFormat.TXT
        "MOBI" -> BookFormat.MOBI
        "CBZ" -> BookFormat.CBZ
        "MD", "MARKDOWN" -> BookFormat.MARKDOWN
        else -> null
    }

    private fun BookFormat.extensionName(): String = when (this) {
        BookFormat.MARKDOWN -> "md"
        else -> name.lowercase()
    }

    private companion object {
        const val KEY_LAST_SYNC = "mybooks_last_sync_at"
        const val PUSH_DEBOUNCE_MS = 1_500L
    }
}
