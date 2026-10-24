package com.huangder.lumibooks.ui.bookshelf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.local.dao.MyBooksSyncStateDao
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import com.huangder.lumibooks.data.sync.ContentUriRequestBody
import com.huangder.lumibooks.data.sync.MyBooksApiClient
import com.huangder.lumibooks.data.sync.MyBooksCoverUploader
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksCreatedBook
import com.huangder.lumibooks.data.sync.MyBooksHttpException
import com.huangder.lumibooks.data.sync.MyBooksMetaCache
import com.huangder.lumibooks.data.sync.MyBooksSyncManager
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.data.sync.normalizeRemoteAuthor
import com.huangder.lumibooks.data.sync.optTextAt
import com.huangder.lumibooks.data.sync.optTextOrEmpty
import com.huangder.lumibooks.data.sync.optTextOrNull
import com.huangder.lumibooks.data.sync.parseRemoteTags
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.TagNameValidator
import com.huangder.lumibooks.domain.repository.TagRepository
import com.huangder.lumibooks.util.FileUtils
import com.huangder.lumibooks.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MyBooksCustomColumn(
    val label: String,
    val name: String,
    val datatype: String,
    val multiple: Boolean,
    val options: List<String>
)

data class MyBooksIdentifierDraft(
    val type: String = "",
    val value: String = ""
)

data class MyBooksReadLogDraft(
    val start: String = "",
    val finish: String = ""
)

data class MyBooksDoubanCandidate(
    val title: String,
    val author: String,
    val summary: String,
    val publisher: String,
    val pubYear: String,
    val isbn: String,
    val rating: Double,
    val doubanId: String,
    val coverUrl: String?,
    val openlibraryId: String?,
    val tags: List<String> = emptyList()
)

data class MyBooksEditUiState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val title: String = "",
    val author: String = "",
    val status: String = "wish",
    val summary: String = "",
    val rating: Float = 0f,
    val series: String = "",
    val seriesIndex: String = "",
    val publisher: String = "",
    val pubYear: String = "",
    val isbn: String = "",
    val doubanId: String = "",
    val identifiers: List<MyBooksIdentifierDraft> = emptyList(),
    val customColumns: List<MyBooksCustomColumn> = emptyList(),
    val customValues: Map<String, String> = emptyMap(),
    val readLogs: List<MyBooksReadLogDraft> = emptyList(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val lookupRunning: Boolean = false,
    val lookupMessage: String? = null,
    val candidates: List<MyBooksDoubanCandidate> = emptyList(),
    /** 豆瓣填充带来的待设置封面（远程 URL），保存时才落地。 */
    val coverUrl: String? = null,
    /** 豆瓣填充带来的标签，保存时写入本地标签 / 服务器。 */
    val tags: List<String> = emptyList(),
    /**
     * 新建成功后服务端分配的书 id 与归一化后的书名/作者。
     * 本地正文副本要绑到同一本书并沿用同一份书名/作者：
     * 否则本地行会退回文件名/未知作者，下一轮同步再把这份文件来源的值推回服务器。
     */
    val created: MyBooksCreatedBook? = null
)

data class MyBooksSyncBadge(
    val pendingCount: Int = 0,
    val lastError: String? = null
)

/** 新建一本服务器书籍所需的来源文件。 */
data class MyBooksCreateSource(
    val fileName: String,
    val uri: Uri
)

@HiltViewModel
class MyBooksEditViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val syncManager: MyBooksSyncManager,
    private val syncStateDao: MyBooksSyncStateDao,
    private val tagRepository: TagRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val api = MyBooksApiClient()
    private val metaCache = MyBooksMetaCache(context)
    private val customColumnsFile = File(context.filesDir, "mybooks/custom_columns.json")

    private val _metaState = MutableStateFlow(MyBooksMetaUiState())
    val metaState: StateFlow<MyBooksMetaUiState> = _metaState.asStateFlow()

    private val _syncBadge = MutableStateFlow(MyBooksSyncBadge())
    val syncBadge: StateFlow<MyBooksSyncBadge> = _syncBadge.asStateFlow()

    private val _uiState = MutableStateFlow(MyBooksEditUiState())
    val uiState: StateFlow<MyBooksEditUiState> = _uiState.asStateFlow()

    private var book: Book? = null
    private var base = JSONObject()
    private var baseline: MyBooksEditUiState? = null
    private var metaLoadedFor: String? = null
    private var metaJob: Job? = null
    private var lookupJob: Job? = null
    private var beginJob: Job? = null

    /** 新建模式：本地文件补充信息后提交到服务器，不写本地 books 表。 */
    private var createSource: MyBooksCreateSource? = null

    fun refreshSyncBadge(bookId: String) {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { syncStateDao.get(bookId) } ?: run {
                _syncBadge.value = MyBooksSyncBadge()
                return@launch
            }
            val overrides = runCatching { JSONObject(state.overridesJson) }.getOrDefault(JSONObject())
            val count = overrides.length() + if (state.coverDirty) 1 else 0
            _syncBadge.value = MyBooksSyncBadge(
                pendingCount = count,
                lastError = state.lastError?.takeIf { it.isNotBlank() }
            )
        }
    }

    fun loadMeta(book: Book, forceRefresh: Boolean = false) {
        if (!MyBooksConfig.isMyBooks(book)) return
        refreshSyncBadge(book.id)
        if (!forceRefresh && metaLoadedFor == book.id && _metaState.value.meta != null) return
        val remoteId = MyBooksConfig.remoteIdOf(book.id) ?: return
        metaLoadedFor = book.id
        metaJob?.cancel()
        metaJob = viewModelScope.launch {
            _metaState.value = _metaState.value.copy(loading = true, retry = { loadMeta(book, true) })
            val overrides = withContext(Dispatchers.IO) {
                syncStateDao.get(book.id)?.overridesJson
                    ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                    ?: JSONObject()
            }
            val cachedRaw = withContext(Dispatchers.IO) { metaCache.read(remoteId) }
            val cachedMeta = cachedRaw?.let {
                runCatching { ServerBookMeta.fromJson(effectiveJson(it, overrides).toString(), remoteId) }.getOrNull()
            }
            if (cachedMeta != null) {
                _metaState.value = MyBooksMetaUiState(cachedMeta, loading = true, retry = { loadMeta(book, true) })
            }
            val fresh = try {
                withContext(Dispatchers.IO) { api.fetchBookJson(remoteId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (fresh != null) {
                withContext(Dispatchers.IO) { metaCache.write(remoteId, fresh) }
                val meta = runCatching {
                    ServerBookMeta.fromJson(effectiveJson(fresh, overrides).toString(), remoteId)
                }.getOrNull()
                _metaState.value = MyBooksMetaUiState(
                    meta = meta ?: cachedMeta,
                    loading = false,
                    retry = { loadMeta(book, true) }
                )
            } else {
                _metaState.value = MyBooksMetaUiState(
                    meta = cachedMeta,
                    loading = false,
                    retry = { loadMeta(book, true) }
                )
            }
        }
    }

    fun begin(book: Book, meta: ServerBookMeta?) {
        beginJob?.cancel()
        this.book = book
        base = JSONObject()
        baseline = null
        _uiState.value = MyBooksEditUiState(loading = true)
        val remoteId = MyBooksConfig.remoteIdOf(book.id)
        if (remoteId == null) {
            _uiState.value = MyBooksEditUiState(
                loading = false,
                loaded = false,
                error = context.getString(R.string.mybooks_edit_load_failed)
            )
            return
        }
        beginJob = viewModelScope.launch {
            try {
                val state = withContext(Dispatchers.IO) { syncStateDao.get(book.id) }
                val rawBase = state?.baseJson
                    ?: withContext(Dispatchers.IO) { metaCache.read(remoteId) }
                    ?: withContext(Dispatchers.IO) {
                        runCatching { api.fetchBookJson(remoteId) }.getOrNull()
                    }
                    ?: buildBaseFromMeta(meta)
                // 加载期间可能已切到另一本书；丢弃本次结果，避免上一本的数据覆盖当前草稿。
                if (this@MyBooksEditViewModel.book?.id != book.id) return@launch
                if (rawBase == null) {
                    _uiState.value = MyBooksEditUiState(
                        loading = false,
                        loaded = false,
                        error = context.getString(R.string.mybooks_edit_load_failed)
                    )
                    return@launch
                }
                base = runCatching { JSONObject(rawBase) }.getOrDefault(JSONObject())
                if (state == null) {
                    withContext(Dispatchers.IO) {
                        syncStateDao.upsert(
                            MyBooksSyncStateEntity(
                                bookId = book.id,
                                remoteId = remoteId,
                                serverUpdatedAt = base.optTextOrEmpty("updated_at"),
                                baseJson = base.toString(),
                                overridesJson = "{}",
                                editedAtJson = "{}",
                                baseTagsJson = JSONArray(meta?.tags.orEmpty()).toString()
                            )
                        )
                    }
                }
                val overrides = state?.overridesJson
                    ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                val cachedColumns = withContext(Dispatchers.IO) { readCachedColumns() }
                if (this@MyBooksEditViewModel.book?.id != book.id) return@launch
                val draft = buildDraft(book, overrides, cachedColumns)
                baseline = draft
                _uiState.value = draft.copy(loading = false, loaded = true, dirty = false)
                refreshCustomColumns()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (this@MyBooksEditViewModel.book?.id != book.id) return@launch
                _uiState.value = MyBooksEditUiState(
                    loading = false,
                    loaded = false,
                    error = error.message ?: context.getString(R.string.mybooks_edit_load_failed)
                )
            }
        }
    }

    /** 新建模式：草稿从文件名起步，确认后提交到服务器并上传正文。 */
    fun beginCreate(source: MyBooksCreateSource) {
        beginJob?.cancel()
        createSource = source
        book = null
        base = JSONObject()
        baseline = null
        _uiState.value = MyBooksEditUiState(
            loading = false,
            loaded = true,
            title = source.fileName.substringBeforeLast('.'),
            author = "",
            status = "wish"
        )
        // 自定义字段定义同样要在新建模式里加载，否则该区块无法填写。
        refreshCustomColumns()
    }

    private fun saveCreate(onSaved: (String?) -> Unit) {
        val current = _uiState.value
        if (current.saving) return
        val errors = validate(current)
        if (errors.isNotEmpty()) {
            _uiState.value = current.copy(fieldErrors = errors, error = errors.values.firstOrNull())
            onSaved(null)
            return
        }
        val source = createSource ?: return
        _uiState.value = current.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            try {
                val token = MyBooksConfig.adminToken
                require(token.isNotBlank()) { context.getString(R.string.mybooks_readonly) }
                val payload = buildCreatePayload(current)
                // 豆瓣填充带回来的封面：先下载再上传，建书时直接写进 cover。
                current.coverUrl?.let { coverUrl ->
                    uploadDoubanCover(coverUrl, token)?.let { payload.put("cover", it) }
                }
                val created = api.createBook(payload, token)
                val remoteId = created.id
                try {
                    api.uploadBookFile(
                        remoteId = remoteId,
                        fileName = source.fileName,
                        content = ContentUriRequestBody(context.contentResolver, source.uri),
                        token = token
                    )
                } catch (error: Exception) {
                    // 正文没传上去就把刚建的书删掉，别在服务器留一个没有正文的空壳。
                    runCatching { api.deleteBook(remoteId, token) }
                    throw error
                }
                createSource = null
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    dirty = false,
                    created = created
                )
                onSaved(context.getString(R.string.mybooks_create_done))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = error.message ?: context.getString(R.string.mybooks_edit_save_failed)
                )
                onSaved(null)
            }
        }
    }

    /** 豆瓣 tags 可能是数组或分隔串，统一解析成去重后的标签名。 */
    private fun parseDoubanTags(raw: Any?): List<String> {
        val values = when (raw) {
            is JSONArray -> buildList { for (i in 0 until raw.length()) raw.optTextAt(i)?.let(::add) }
            is String -> raw.split('、', ',', '，', ';', '；')
            else -> emptyList()
        }
        return values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** 下载豆瓣封面并上传到服务器，返回服务端封面 URL。 */
    private suspend fun uploadDoubanCover(coverUrl: String, token: String): String? = runCatching {
        val temp = File(context.cacheDir, "mybooks/douban-cover-${System.currentTimeMillis()}.jpg")
        if (api.fetchCover(coverUrl, temp)) {
            val uploaded = MyBooksCoverUploader(api).upload(temp, token)
            temp.delete()
            uploaded
        } else {
            null
        }
    }.getOrNull()

    /** 下载豆瓣封面到本地封面目录，交给同步流程上传到服务器。 */
    private suspend fun downloadDoubanCover(coverUrl: String, remoteId: Long): String? = runCatching {
        val covers = FileUtils.getCoversDirectory(context)
        val dest = File(covers, "mybooks_cover_${remoteId}_${Integer.toHexString(coverUrl.hashCode())}.jpg")
        if (api.fetchCover(coverUrl, dest)) dest.absolutePath else null
    }.getOrNull()

    /** 新建书籍的请求体：服务端 parseBook/parseCustom 的字段形态。 */
    private fun buildCreatePayload(state: MyBooksEditUiState): JSONObject {
        val payload = JSONObject()
            .put("title", state.title.trim())
            .put("author", state.author.trim())
            .put("status", state.status)
            .put("summary", state.summary.trim())
            .put("rating", state.rating.toDouble())
            .put("series", state.series.trim())
            .put("series_index", state.seriesIndex.trim())
            .put("publisher", state.publisher.trim())
            .put("pub_year", state.pubYear.trim())
            .put("isbn", state.isbn.trim())
            .put("douban_id", state.doubanId.trim())
        val identifiers = JSONObject()
        state.identifiers.forEach { row ->
            val type = row.type.trim().lowercase(Locale.ROOT)
            if (type.isNotEmpty() && row.value.isNotBlank()) identifiers.put(type, row.value.trim())
        }
        payload.put("identifiers", identifiers)
        payload.put("tags", JSONArray(state.tags))
        val custom = JSONObject()
        state.customColumns.forEach { column ->
            val value = state.customValues[column.label].orEmpty()
            if (value.isNotBlank()) custom.put(column.label, customToServerValue(column, value))
        }
        payload.put("custom", custom)
        val logs = JSONArray()
        state.readLogs.forEach { row ->
            val start = row.start.trim()
            val finish = row.finish.trim()
            if (start.isNotEmpty() || finish.isNotEmpty()) {
                logs.put(JSONObject().put("start", start).put("finish", finish))
            }
        }
        payload.put("read_logs", logs)
        return payload
    }

    fun updateTitle(value: String) = mutate { it.copy(title = value) }
    fun updateAuthor(value: String) = mutate { it.copy(author = value) }
    fun updateStatus(value: String) = mutate { it.copy(status = value) }
    fun updateSummary(value: String) = mutate { it.copy(summary = value) }
    fun updateRating(value: Float) = mutate { it.copy(rating = value.coerceIn(0f, 10f)) }
    fun updateSeries(value: String) = mutate {
        it.copy(series = value, seriesIndex = if (value.isBlank()) "" else it.seriesIndex)
    }
    fun updateSeriesIndex(value: String) = mutate { it.copy(seriesIndex = value) }
    fun updatePublisher(value: String) = mutate { it.copy(publisher = value) }
    fun updatePubYear(value: String) = mutate { it.copy(pubYear = value) }
    fun updateIsbn(value: String) = mutate { it.copy(isbn = value) }
    fun updateDoubanId(value: String) = mutate { it.copy(doubanId = value) }

    fun updateIdentifier(index: Int, type: String? = null, value: String? = null) = mutate { state ->
        val rows = state.identifiers.toMutableList()
        val row = rows.getOrNull(index) ?: return@mutate state
        rows[index] = row.copy(
            type = type ?: row.type,
            value = value ?: row.value
        )
        state.copy(identifiers = rows)
    }

    fun addIdentifier() = mutate { it.copy(identifiers = it.identifiers + MyBooksIdentifierDraft()) }

    fun removeIdentifier(index: Int) = mutate { state ->
        val rows = state.identifiers.toMutableList()
        if (index in rows.indices) rows.removeAt(index)
        state.copy(identifiers = rows)
    }

    fun updateCustom(label: String, value: String) = mutate { state ->
        state.copy(customValues = state.customValues + (label to value))
    }

    fun updateReadLog(index: Int, start: String? = null, finish: String? = null) = mutate { state ->
        val rows = state.readLogs.toMutableList()
        val row = rows.getOrNull(index) ?: return@mutate state
        rows[index] = row.copy(start = start ?: row.start, finish = finish ?: row.finish)
        val status = when {
            !finish.isNullOrBlank() && state.status == "reading" -> "done"
            finish != null && finish.isBlank() && state.status == "done" &&
                rows.none { it.finish.isNotBlank() } -> "reading"
            else -> state.status
        }
        state.copy(readLogs = rows, status = status)
    }

    fun addReadLog() = mutate { state ->
        state.copy(
            readLogs = state.readLogs + MyBooksReadLogDraft(),
            status = if (state.status == "wish") "reading" else state.status
        )
    }

    fun removeReadLog(index: Int) = mutate { state ->
        val rows = state.readLogs.toMutableList()
        if (index in rows.indices) rows.removeAt(index)
        state.copy(
            readLogs = rows,
            status = if (state.status == "done" && rows.none { it.finish.isNotBlank() }) "reading" else state.status
        )
    }

    fun save(onSaved: (String?) -> Unit) {
        if (createSource != null) {
            saveCreate(onSaved)
            return
        }
        val current = _uiState.value
        if (current.saving) return
        val errors = validate(current)
        if (errors.isNotEmpty()) {
            _uiState.value = current.copy(fieldErrors = errors, error = errors.values.firstOrNull())
            onSaved(null)
            return
        }
        val activeBook = book ?: run {
            onSaved(context.getString(R.string.mybooks_edit_save_failed))
            return
        }
        _uiState.value = current.copy(saving = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            val changed = linkedMapOf<String, Any?>()
            val managed = linkedSetOf<String>()
            try {
                val existingOverrides = withContext(Dispatchers.IO) {
                    syncStateDao.get(activeBook.id)?.overridesJson
                        ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                        ?: JSONObject()
                }
                collectScalarChanges(current, changed, managed)
                collectIdentifierChanges(current, changed, managed, existingOverrides)
                collectCustomChanges(current, changed, managed, existingOverrides)
                collectReadLogChanges(current, changed, managed)
                withContext(Dispatchers.IO) {
                    syncManager.replaceLocalEdits(activeBook.id, changed, managed)
                    // 被改动的字段用草稿值；没改动的字段用服务器基线值。
                    // 不能沿用本地行原值：它可能还残留着导入正文副本时按文件名写入的临时值，
                    // 写回去会被下一轮同步当成"本地编辑"推回服务器，覆盖掉豆瓣填好的书名作者。
                    val newTitle = if (changed.containsKey("title")) {
                        current.title.trim().ifBlank { activeBook.title }
                    } else {
                        base.optTextOrEmpty("title").ifBlank { activeBook.title }
                    }
                    val newAuthor = if (changed.containsKey("author")) {
                        current.author.trim()
                    } else {
                        normalizeRemoteAuthor(base.optTextOrEmpty("author"))
                    }
                    bookRepository.updateBookTitleAuthor(activeBook.id, newTitle, newAuthor)
                    book = activeBook.copy(title = newTitle, author = newAuthor)
                    // 豆瓣带回来的标签：写成本地标签，合并流程会把新增标签推到服务器。
                    val tagCatalog = tagRepository.getAllTags().first()
                    val seenTags = mutableSetOf<String>()
                    current.tags.forEach { rawName ->
                        if (!TagNameValidator.isValid(rawName)) return@forEach
                        val normalized = TagNameValidator.normalized(rawName)
                        if (!seenTags.add(normalized)) return@forEach
                        val existingTag = tagCatalog.firstOrNull {
                            TagNameValidator.normalized(it.name) == normalized
                        }
                        runCatching {
                            if (existingTag != null) {
                                tagRepository.assignTag(activeBook.id, existingTag.id)
                            } else {
                                tagRepository.createAndAssignTag(activeBook.id, rawName)
                            }
                        }
                    }
                    if (seenTags.isNotEmpty()) syncManager.schedulePush()
                    // 豆瓣填充带回来的封面：落到本地封面目录，再由同步流程上传到服务器。
                    val remoteId = MyBooksConfig.remoteIdOf(activeBook.id)
                    if (remoteId != null && current.coverUrl != null) {
                        downloadDoubanCover(current.coverUrl, remoteId)?.let { path ->
                            bookRepository.updateBookCover(activeBook.id, path)
                            syncManager.recordCoverChange(activeBook.id)
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(saving = false, dirty = false)
                refreshSyncBadge(activeBook.id)
                metaLoadedFor = null
                loadMeta(activeBook, forceRefresh = true)
                onSaved(
                    context.getString(
                        if (MyBooksConfig.hasWriteToken()) R.string.mybooks_edit_saved
                        else R.string.mybooks_edit_saved_readonly
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = error.message ?: context.getString(R.string.mybooks_edit_save_failed)
                )
                onSaved(null)
            }
        }
    }

    fun lookupDouban() {
        val state = _uiState.value
        if (state.lookupRunning) return
        val isbn = state.isbn.trim()
        val query = state.title.trim()
        if (isbn.isBlank() && query.isBlank()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                lookupRunning = true,
                lookupMessage = null,
                candidates = emptyList()
            )
            try {
                if (isbn.isNotBlank()) {
                    val raw = withContext(Dispatchers.IO) { api.lookupIsbn(isbn) }
                    val obj = JSONObject(raw)
                    if (obj.optString("status") == "found") {
                        val candidate = parseCandidate(obj.optJSONObject("book")) 
                        if (candidate != null) {
                            applyCandidate(candidate)
                            _uiState.value = _uiState.value.copy(
                                lookupRunning = false,
                                lookupMessage = context.getString(R.string.mybooks_douban_filled)
                            )
                            return@launch
                        }
                    }
                    val source = obj.optString("source")
                    if (query.isBlank()) {
                        _uiState.value = _uiState.value.copy(
                            lookupRunning = false,
                            lookupMessage = if (obj.optString("status") == "blocked") {
                                context.getString(R.string.mybooks_douban_blocked)
                            } else {
                                context.getString(R.string.mybooks_douban_not_found)
                            }
                        )
                        return@launch
                    }
                    if (source == "douban" || obj.optString("status") == "blocked") {
                        // 豆瓣被拒时不再自动转书名检索，提示用户手动重试。
                        if (obj.optString("status") == "blocked") {
                            _uiState.value = _uiState.value.copy(
                                lookupRunning = false,
                                lookupMessage = context.getString(R.string.mybooks_douban_blocked)
                            )
                            return@launch
                        }
                    }
                }
                searchByTitle(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    lookupRunning = false,
                    lookupMessage = doubanError(error)
                )
            }
        }
    }

    fun searchByTitle(query: String = _uiState.value.title.trim()) {
        if (query.isBlank()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                lookupRunning = true,
                lookupMessage = null,
                candidates = emptyList()
            )
            try {
                val raw = withContext(Dispatchers.IO) { api.searchMetadata(query) }
                val obj = JSONObject(raw)
                val results = obj.optJSONArray("results") ?: JSONArray()
                val candidates = buildList {
                    for (i in 0 until results.length()) {
                        parseCandidate(results.optJSONObject(i))?.let(::add)
                    }
                }
                _uiState.value = _uiState.value.copy(
                    lookupRunning = false,
                    candidates = candidates,
                    lookupMessage = when {
                        candidates.isNotEmpty() -> null
                        obj.optBoolean("cookieless") ->
                            context.getString(R.string.mybooks_douban_cookieless)
                        else -> context.getString(R.string.mybooks_douban_not_found)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    lookupRunning = false,
                    lookupMessage = doubanError(error)
                )
            }
        }
    }

    fun applyCandidate(candidate: MyBooksDoubanCandidate) {
        mutate { state ->
            var identifiers = state.identifiers
            if (!candidate.openlibraryId.isNullOrBlank()) {
                val index = identifiers.indexOfFirst { it.type.equals("openlibrary", true) }
                identifiers = if (index >= 0) {
                    identifiers.toMutableList().also {
                        it[index] = it[index].copy(value = candidate.openlibraryId)
                    }
                } else {
                    identifiers + MyBooksIdentifierDraft("openlibrary", candidate.openlibraryId)
                }
            }
            state.copy(
                title = candidate.title.ifBlank { state.title },
                author = candidate.author.ifBlank { state.author },
                summary = candidate.summary.ifBlank { state.summary },
                publisher = candidate.publisher.ifBlank { state.publisher },
                pubYear = candidate.pubYear.ifBlank { state.pubYear },
                isbn = candidate.isbn.ifBlank { state.isbn },
                doubanId = candidate.doubanId.ifBlank { state.doubanId },
                rating = if (candidate.rating > 0) candidate.rating.toFloat() else state.rating,
                identifiers = identifiers,
                candidates = emptyList(),
                coverUrl = candidate.coverUrl?.takeIf { it.isNotBlank() } ?: state.coverUrl,
                tags = (state.tags + candidate.tags)
                    .map { TagNameValidator.clean(it) }
                    .filter { TagNameValidator.isValid(it) }
                    .distinctBy { TagNameValidator.normalized(it) },
                lookupMessage = context.getString(R.string.mybooks_douban_filled)
            )
        }
    }

    fun clearCandidates() {
        _uiState.value = _uiState.value.copy(candidates = emptyList())
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null, lookupMessage = null)
    }

    fun dismiss() {
        lookupJob?.cancel()
        beginJob?.cancel()
        createSource = null
        book = null
        base = JSONObject()
        baseline = null
        _uiState.value = MyBooksEditUiState(loading = false)
    }

    // ────────────────────────── drafts ──────────────────────────

    private fun buildDraft(
        book: Book,
        overrides: JSONObject,
        columns: List<MyBooksCustomColumn>
    ): MyBooksEditUiState {
        fun value(key: String): String = when {
            overrides.has(key) -> overrides.optTextOrEmpty(key)
            else -> base.optTextOrEmpty(key)
        }
        val baseRating = when {
            overrides.has("rating") -> overrides.optDouble("rating", 0.0)
            else -> base.optDouble("rating", 0.0)
        }
        val baseIds = baseIdentifiers()
        val draftIds = mutableListOf<MyBooksIdentifierDraft>()
        for ((type, raw) in baseIds) {
            val key = MyBooksConfig.identifierOverrideKey(type)
            val value = if (overrides.has(key)) overrides.optTextOrEmpty(key) else raw
            if (value.isNotBlank()) draftIds += MyBooksIdentifierDraft(type, value)
        }
        overrides.keys().forEach { key ->
            if (!key.startsWith("identifiers.")) return@forEach
            val type = key.removePrefix("identifiers.")
            if (type == "isbn" || type == "douban" || type == "douban_id") return@forEach
            val value = overrides.optTextOrEmpty(key)
            if (value.isBlank()) return@forEach
            if (draftIds.none { it.type.equals(type, true) }) {
                draftIds += MyBooksIdentifierDraft(type, value)
            }
        }
        val customValues = buildMap {
            val baseCustom = base.optJSONObject("custom") ?: JSONObject()
            columns.forEach { column ->
                val key = MyBooksConfig.customOverrideKey(column.label)
                val raw = if (overrides.has(key)) overrides.opt(key) else baseCustom.opt(column.label)
                put(column.label, customToDraft(column, raw))
            }
        }
        val logs = readLogsFrom(if (overrides.has("read_logs")) overrides.opt("read_logs") else base.opt("read_logs"))
        val status = value("status").ifBlank { "wish" }
        return MyBooksEditUiState(
            loading = false,
            loaded = true,
            // 草稿的书名/作者以服务器基线为准：本地行可能还残留着导入正文副本时
            // 按文件名写入的临时值，用它做兜底会把文件来源的值带进草稿。
            title = value("title").ifBlank { book.title.trim() },
            author = value("author").ifBlank { normalizeRemoteAuthor(book.author) },
            status = if (status in setOf("wish", "reading", "done")) status else "wish",
            summary = value("summary"),
            rating = baseRating.toFloat().coerceIn(0f, 10f),
            series = value("series"),
            seriesIndex = value("series_index").takeUnless { it == "0" }.orEmpty(),
            publisher = value("publisher"),
            pubYear = value("pub_year"),
            isbn = value("isbn"),
            doubanId = value("douban_id"),
            identifiers = draftIds,
            customColumns = columns,
            customValues = customValues,
            readLogs = logs.map { MyBooksReadLogDraft(it.first, it.second) },
            tags = parseRemoteTags(base.opt("tags"))
        )
    }

    private fun buildBaseFromMeta(meta: ServerBookMeta?): String? {
        if (meta == null) return null
        val obj = JSONObject()
            .put("title", meta.title)
            .put("author", meta.author)
            .put("status", meta.status.ifBlank { "wish" })
            .put("summary", meta.summary)
            .put("rating", meta.rating ?: 0.0)
            .put("series", meta.series.orEmpty())
            .put("series_index", meta.seriesIndex)
            .put("publisher", meta.publisher.orEmpty())
            .put("pub_year", meta.pubYear.orEmpty())
            .put("isbn", meta.isbn.orEmpty())
            .put("douban_id", meta.doubanId.orEmpty())
            .put("updated_at", meta.updatedAt.orEmpty())
            .put("identifiers", JSONObject(meta.identifiersRaw))
            .put("custom", JSONObject(meta.customRaw))
            .put("read_logs", JSONArray().also { array ->
                meta.readLogs.forEach { log ->
                    array.put(JSONObject().put("start", log.start.orEmpty()).put("finish", log.finish.orEmpty()))
                }
            })
            .put("tags", JSONArray(meta.tags))
        return obj.toString()
    }

    private fun baseIdentifiers(): Map<String, String> {
        val map = base.optJSONObject("identifiers") ?: return emptyMap()
        val keys = map.keys()
        val out = linkedMapOf<String, String>()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals("isbn", true) || key.equals("douban", true) || key.equals("douban_id", true)) continue
            val value = map.optTextOrEmpty(key)
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    // ────────────────────────── save collection ──────────────────────────

    private fun collectScalarChanges(
        state: MyBooksEditUiState,
        changed: MutableMap<String, Any?>,
        managed: MutableSet<String>
    ) {
        val fields = listOf("title", "author", "status", "summary", "rating", "series", "series_index", "publisher", "pub_year", "isbn", "douban_id")
        managed += fields
        val baseValues = mapOf(
            "title" to base.optTextOrEmpty("title").ifBlank { "未命名" },
            "author" to base.optTextOrEmpty("author").ifBlank { "未知作者" },
            "status" to base.optTextOrEmpty("status").ifBlank { "wish" },
            "summary" to base.optTextOrEmpty("summary"),
            "rating" to base.optDouble("rating", 0.0),
            "series" to base.optTextOrEmpty("series"),
            "series_index" to base.opt("series_index")?.toString().takeUnless { it == "null" }.orEmpty(),
            "publisher" to base.optTextOrEmpty("publisher"),
            "pub_year" to base.optTextOrEmpty("pub_year"),
            "isbn" to base.optTextOrEmpty("isbn"),
            "douban_id" to base.optTextOrEmpty("douban_id")
        )
        val draftValues = mapOf(
            "title" to state.title.trim().ifBlank { "未命名" },
            "author" to state.author.trim().ifBlank { "未知作者" },
            "status" to state.status,
            "summary" to state.summary.trim(),
            "rating" to state.rating.toDouble(),
            "series" to state.series.trim(),
            "series_index" to state.seriesIndex.trim(),
            "publisher" to state.publisher.trim(),
            "pub_year" to state.pubYear.trim(),
            "isbn" to state.isbn.trim(),
            "douban_id" to state.doubanId.trim()
        )
        // 比较沿用占位口径（空值与基线等价，不产生幽灵改动）；
        // 真正提交时用用户原始输入，避免把占位文案写进服务器。
        val rawValues = mapOf(
            "title" to state.title.trim(),
            "author" to state.author.trim()
        )
        for ((key, value) in draftValues) {
            val baseValue = baseValues[key]
            val equal = when (key) {
                "rating" -> (baseValue as? Number)?.toDouble() == (value as Double)
                "series_index" -> (baseValue as? String).orEmpty().ifBlank { "0" } == (value as String).ifBlank { "0" }
                else -> baseValue == value
            }
            if (!equal) changed[key] = rawValues[key] ?: value
        }
    }

    private fun collectIdentifierChanges(
        state: MyBooksEditUiState,
        changed: MutableMap<String, Any?>,
        managed: MutableSet<String>,
        existingOverrides: JSONObject
    ) {
        val baseIds = baseIdentifiers()
        baseIds.keys.forEach { managed += MyBooksConfig.identifierOverrideKey(it) }
        existingOverrides.keys().forEach { key ->
            if (key.startsWith("identifiers.")) managed += key
        }
        val draft = linkedMapOf<String, String>()
        state.identifiers.forEach { row ->
            val type = row.type.trim().lowercase(Locale.ROOT)
            if (type.isEmpty() && row.value.isBlank()) return@forEach
            if (type.isNotEmpty()) draft[type] = row.value.trim()
            managed += MyBooksConfig.identifierOverrideKey(type)
        }
        val types = baseIds.keys + draft.keys
        for (type in types) {
            val baseValue = baseIds[type].orEmpty()
            val value = draft[type].orEmpty()
            if (baseValue == value) continue
            // override 存扁平 key，合并/回读都按 identifiers.<type> 找；服务端 PATCH 再由合并层组装成对象。
            changed[MyBooksConfig.identifierOverrideKey(type)] = value
        }
    }

    private fun collectCustomChanges(
        state: MyBooksEditUiState,
        changed: MutableMap<String, Any?>,
        managed: MutableSet<String>,
        existingOverrides: JSONObject
    ) {
        if (state.customColumns.isEmpty()) return
        existingOverrides.keys().forEach { key ->
            if (key.startsWith("custom.")) managed += key
        }
        val baseCustom = base.optJSONObject("custom") ?: JSONObject()
        for (column in state.customColumns) {
            val key = MyBooksConfig.customOverrideKey(column.label)
            managed += key
            val draft = state.customValues[column.label].orEmpty()
            val raw = baseCustom.opt(column.label)
            if (customDraftEquals(column, draft, raw)) continue
            // 与 identifiers 同理：override 用扁平 key，多值字段的值形态交给合并层转换。
            changed[key] = customToServerValue(column, draft)
        }
    }

    private fun collectReadLogChanges(
        state: MyBooksEditUiState,
        changed: MutableMap<String, Any?>,
        managed: MutableSet<String>
    ) {
        managed += "read_logs"
        val draft = state.readLogs
            .map { it.start.trim() to it.finish.trim() }
            .filter { it.first.isNotBlank() || it.second.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        val baseLogs = readLogsFrom(base.opt("read_logs"))
        if (draft == baseLogs) return
        val array = JSONArray()
        draft.forEach { (start, finish) ->
            array.put(JSONObject().put("start", start).put("finish", finish))
        }
        changed["read_logs"] = array
    }

    // ────────────────────────── validation ──────────────────────────

    private fun validate(state: MyBooksEditUiState): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        if (state.title.trim().isEmpty()) {
            errors["title"] = context.getString(R.string.mybooks_edit_title_required)
        }
        if (state.summary.length > 1000) {
            errors["summary"] = context.getString(R.string.mybooks_edit_summary_too_long)
        }
        if (state.pubYear.length > 20) {
            errors["pub_year"] = context.getString(R.string.mybooks_edit_text_too_long, 20)
        }
        if (state.isbn.length > 20) {
            errors["isbn"] = context.getString(R.string.mybooks_edit_text_too_long, 20)
        }
        if (state.doubanId.length > 32) {
            errors["douban_id"] = context.getString(R.string.mybooks_edit_text_too_long, 32)
        }
        if (state.seriesIndex.isNotBlank() && (state.seriesIndex.trim().toIntOrNull() ?: -1) < 0) {
            errors["series_index"] = context.getString(R.string.mybooks_edit_series_index_invalid)
        }
        if (state.seriesIndex.isNotBlank() && state.series.trim().isEmpty() &&
            state.seriesIndex != baseline?.seriesIndex.orEmpty()
        ) {
            errors["series_index"] = context.getString(R.string.mybooks_edit_series_required)
        }
        state.identifiers.forEachIndexed { index, row ->
            if (row.type.isBlank() && row.value.isBlank()) return@forEachIndexed
            val type = row.type.trim().lowercase(Locale.ROOT)
            if (!IDENTIFIER_TYPE.matches(type)) {
                errors["identifier.$index"] = context.getString(R.string.mybooks_edit_identifier_type_invalid)
            }
            if (row.value.trim().length > 200) {
                errors["identifier.$index"] = context.getString(R.string.mybooks_edit_identifier_value_too_long)
            }
        }
        state.readLogs.forEachIndexed { index, row ->
            val start = row.start.trim()
            val finish = row.finish.trim()
            if (start.isEmpty() && finish.isEmpty()) return@forEachIndexed
            if ((start.isNotEmpty() && !isDate(start)) || (finish.isNotEmpty() && !isDate(finish))) {
                errors["readlog.$index"] = context.getString(R.string.mybooks_edit_date_invalid)
            } else if (start.isNotEmpty() && finish.isNotEmpty() && finish < start) {
                errors["readlog.$index"] = context.getString(R.string.mybooks_edit_date_order)
            }
        }
        state.customColumns.forEach { column ->
            val value = state.customValues[column.label].orEmpty().trim()
            if (value.isEmpty()) return@forEach
            val invalid = when (column.datatype) {
                "int" -> value.toLongOrNull() == null
                "float" -> value.toDoubleOrNull() == null
                "rating" -> value.toDoubleOrNull()?.let { it < 0 || it > 10 } ?: true
                "date" -> !isDate(value)
                "enumeration" -> column.options.none { it == value }
                else -> false
            }
            if (invalid) {
                errors[MyBooksConfig.customOverrideKey(column.label)] = context.getString(R.string.mybooks_edit_custom_invalid, column.name)
            }
        }
        return errors
    }

    private fun isDate(value: String): Boolean =
        DATE.matches(value) && runCatching { LocalDate.parse(value) }.isSuccess

    // ────────────────────────── helpers ──────────────────────────

    private fun mutate(block: (MyBooksEditUiState) -> MyBooksEditUiState) {
        val next = block(_uiState.value).copy(error = null, fieldErrors = emptyMap())
        val baseDraft = baseline
        _uiState.value = next.copy(dirty = baseDraft != null && !draftEquals(next, baseDraft))
    }

    private fun draftEquals(a: MyBooksEditUiState, b: MyBooksEditUiState): Boolean =
        a.title == b.title &&
            a.author == b.author &&
            a.status == b.status &&
            a.summary == b.summary &&
            a.rating == b.rating &&
            a.series == b.series &&
            a.seriesIndex == b.seriesIndex &&
            a.publisher == b.publisher &&
            a.pubYear == b.pubYear &&
            a.isbn == b.isbn &&
            a.doubanId == b.doubanId &&
            a.identifiers == b.identifiers &&
            a.customValues == b.customValues &&
            a.readLogs == b.readLogs &&
            a.tags == b.tags &&
            a.coverUrl == b.coverUrl

    private fun customToDraft(column: MyBooksCustomColumn, raw: Any?): String = when {
        raw == null || raw == JSONObject.NULL -> ""
        column.multiple && raw is JSONArray -> buildList {
            for (i in 0 until raw.length()) {
                raw.optTextAt(i)?.let(::add)
            }
        }.joinToString("、")
        raw is JSONArray -> buildList {
            for (i in 0 until raw.length()) {
                raw.optTextAt(i)?.let(::add)
            }
        }.joinToString("、")
        column.datatype == "bool" -> when (raw) {
            is Boolean -> if (raw) "1" else "0"
            is Number -> if (raw.toInt() != 0) "1" else "0"
            else -> when (raw.toString().trim().lowercase(Locale.ROOT)) {
                "true", "1", "yes", "是" -> "1"
                "false", "0", "no", "否" -> "0"
                else -> ""
            }
        }
        column.datatype == "rating" || column.datatype == "float" ->
            (raw as? Number)?.toString() ?: raw.toString()
        else -> raw.toString()
    }

    private fun customDraftEquals(column: MyBooksCustomColumn, draft: String, raw: Any?): Boolean {
        val text = draft.trim()
        return when (column.datatype) {
            "bool" -> {
                val base = customToDraft(column, raw)
                base == text
            }
            "int" -> {
                val base = (raw as? Number)?.toLong() ?: raw?.toString()?.toLongOrNull()
                val current = text.toLongOrNull()
                base == current
            }
            "float", "rating" -> {
                val base = (raw as? Number)?.toDouble() ?: raw?.toString()?.toDoubleOrNull()
                val current = text.toDoubleOrNull()
                base == current || (base == null && text.isEmpty())
            }
            else -> {
                if (column.multiple) {
                    val base = customToDraft(column, raw).split('、', ',', '，', ';', '；')
                        .map { it.trim() }.filter { it.isNotEmpty() }.sorted()
                    val current = text.split('、', ',', '，', ';', '；')
                        .map { it.trim() }.filter { it.isNotEmpty() }.sorted()
                    base == current
                } else {
                    customToDraft(column, raw).trim() == text
                }
            }
        }
    }

    private fun customToServerValue(column: MyBooksCustomColumn, draft: String): Any {
        val text = draft.trim()
        return when {
            column.multiple -> JSONArray(
                text.split('、', ',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
            )
            column.datatype == "int" -> if (text.isEmpty()) "" else text.toLongOrNull() ?: 0L
            column.datatype == "float" || column.datatype == "rating" ->
                if (text.isEmpty()) "" else text.toDoubleOrNull() ?: 0.0
            column.datatype == "bool" -> if (text.isEmpty()) "" else text == "1"
            else -> text
        }
    }

    private fun readLogsFrom(raw: Any?): List<Pair<String, String>> {
        val array = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val start = item.optTextOrEmpty("start").trim()
            val finish = item.optTextOrEmpty("finish").trim()
            if (start.isEmpty() && finish.isEmpty()) continue
            out += start to finish
        }
        return out.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun effectiveJson(raw: String, overrides: JSONObject): JSONObject {
        val baseObj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val effective = JSONObject(baseObj.toString())
        if (overrides.length() == 0) return effective
        listOf(
            "title", "author", "status", "summary", "rating", "series", "series_index",
            "publisher", "pub_year", "isbn", "douban_id"
        ).forEach { key ->
            if (overrides.has(key)) effective.put(key, overrides.opt(key))
        }
        val ids = JSONObject(effective.optJSONObject("identifiers")?.toString() ?: "{}")
        overrides.keys().forEach { key ->
            if (key.startsWith("identifiers.")) {
                val type = key.removePrefix("identifiers.")
                val value = overrides.optTextOrEmpty(key)
                if (value.isBlank()) ids.remove(type) else ids.put(type, value)
            }
        }
        effective.put("identifiers", ids)
        effective.remove("identifier_list")
        val custom = JSONObject(effective.optJSONObject("custom")?.toString() ?: "{}")
        overrides.keys().forEach { key ->
            if (key.startsWith("custom.")) {
                val label = key.removePrefix("custom.")
                val value = overrides.opt(key)
                if (isEmptyCustomValue(value)) custom.remove(label) else custom.put(label, value)
            }
        }
        effective.put("custom", custom)
        val display = JSONObject()
        custom.keys().forEach { label ->
            val value = custom.opt(label)
            val text = when (value) {
                is JSONArray -> buildList {
                    for (i in 0 until value.length()) {
                        value.optTextAt(i)?.let(::add)
                    }
                }.joinToString("、")
                null, JSONObject.NULL -> ""
                else -> value.toString()
            }.trim()
            if (text.isNotEmpty()) display.put(label, text)
        }
        effective.put("custom_display", display)
        if (overrides.has("read_logs")) {
            val logs = readLogsFrom(overrides.opt("read_logs"))
            effective.put("read_logs", overrides.opt("read_logs"))
            val finished = logs.mapNotNull { it.second.takeIf(String::isNotBlank) }
            effective.put("read_count", finished.size)
            effective.put("finish_date", finished.maxOrNull().orEmpty())
            effective.put("start_date", logs.mapNotNull { it.first.takeIf(String::isNotBlank) }.maxOrNull().orEmpty())
        }
        return effective
    }

    private fun isEmptyCustomValue(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> true
        is JSONArray -> value.length() == 0
        is String -> value.isBlank()
        else -> false
    }

    private fun refreshCustomColumns() {
        viewModelScope.launch {
            val raw = try {
                withContext(Dispatchers.IO) { api.fetchCustomFields() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (raw != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        customColumnsFile.parentFile?.mkdirs()
                        customColumnsFile.writeText(raw, Charsets.UTF_8)
                    }
                }
                val columns = runCatching { parseColumns(raw) }.getOrDefault(emptyList())
                if (columns.isNotEmpty()) {
                    val current = _uiState.value
                    val values = buildMap {
                        columns.forEach { column ->
                            put(column.label, current.customValues[column.label] ?: "")
                        }
                    }
                    val baselineValues = buildMap {
                        columns.forEach { column ->
                            put(
                                column.label,
                                baseline?.customValues?.get(column.label)
                                    ?: current.customValues[column.label]
                                    ?: ""
                            )
                        }
                    }
                    baseline = baseline?.copy(customColumns = columns, customValues = baselineValues)
                    _uiState.value = current.copy(
                        customColumns = columns,
                        customValues = values
                    ).recalculateDirty(baseline)
                }
            }
        }
    }

    private fun readCachedColumns(): List<MyBooksCustomColumn> {
        if (!customColumnsFile.isFile) return emptyList()
        return runCatching { parseColumns(customColumnsFile.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    private fun parseColumns(raw: String): List<MyBooksCustomColumn> {
        val array = JSONArray(raw)
        val out = mutableListOf<MyBooksCustomColumn>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val label = item.optTextOrEmpty("label").trim()
            if (label.isEmpty()) continue
            val options = item.optJSONArray("options")?.let { opts ->
                buildList {
                    for (j in 0 until opts.length()) {
                        opts.optTextAt(j)?.let(::add)
                    }
                }
            }.orEmpty()
            out += MyBooksCustomColumn(
                label = label,
                name = item.optTextOrEmpty("name").ifBlank { label },
                datatype = item.optTextOrEmpty("datatype").ifBlank { "text" },
                multiple = item.optInt("is_multiple", 0) != 0,
                options = options
            )
        }
        return out
    }

    private fun MyBooksEditUiState.recalculateDirty(baseDraft: MyBooksEditUiState?): MyBooksEditUiState =
        copy(dirty = baseDraft != null && !draftEquals(this, baseDraft))

    private fun parseCandidate(obj: JSONObject?): MyBooksDoubanCandidate? {
        if (obj == null) return null
        val title = obj.optTextOrEmpty("title").trim()
        if (title.isEmpty()) return null
        return MyBooksDoubanCandidate(
            title = title,
            author = obj.optTextOrEmpty("author").trim(),
            summary = obj.optTextOrEmpty("summary").trim(),
            publisher = obj.optTextOrEmpty("publisher").trim(),
            pubYear = obj.optTextOrEmpty("pubYear").ifBlank { obj.optTextOrEmpty("pub_year") }.trim(),
            isbn = obj.optTextOrEmpty("isbn").trim(),
            rating = obj.optDouble("rating", 0.0),
            doubanId = obj.optTextOrEmpty("doubanId").ifBlank { obj.optTextOrEmpty("douban_id") }.trim(),
            coverUrl = obj.optTextOrEmpty("cover").takeIf { it.isNotBlank() },
            openlibraryId = obj.optTextOrEmpty("openlibraryId").ifBlank { obj.optTextOrEmpty("openlibrary_id") }
                .takeIf { it.isNotBlank() },
            tags = parseDoubanTags(obj.opt("tags"))
        )
    }

    private fun doubanError(error: Exception): String = when ((error as? MyBooksHttpException)?.status) {
        502 -> context.getString(R.string.mybooks_douban_blocked)
        504 -> context.getString(R.string.mybooks_douban_timeout)
        400 -> context.getString(R.string.mybooks_douban_bad_isbn)
        else -> error.message ?: context.getString(R.string.mybooks_douban_failed)
    }

    private companion object {
        val IDENTIFIER_TYPE = Regex("^[a-z0-9_\\-.]{1,32}$")
        val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
