package com.huangder.lumibooks.ui.bookshelf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksHttpException
import com.huangder.lumibooks.data.sync.MyBooksNotesPayload
import com.huangder.lumibooks.data.sync.MyBooksSyncManager
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.util.markdown.MarkdownRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MyBooksNotesUiState(
    val loading: Boolean = false,
    val localHas: Boolean = false,
    val localSize: Long = 0,
    val serverHas: Boolean = false,
    val serverUpdatedAt: String? = null,
    val serverSize: Long = 0,
    val upToDate: Boolean = false,
    val syncing: Boolean = false,
    val uploading: Boolean = false,
    val error: String? = null,
    val errorWasUpload: Boolean = false,
    val bodyHtml: String? = null
) {
    val busy: Boolean get() = syncing || uploading
}

data class MyBooksPickedNote(
    val displayName: String,
    val size: Long,
    val content: String
)

@HiltViewModel
class MyBooksNotesViewModel @Inject constructor(
    private val syncManager: MyBooksSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(MyBooksNotesUiState())
    val uiState: StateFlow<MyBooksNotesUiState> = _uiState.asStateFlow()

    private val _readerState = MutableStateFlow(MyBooksNotesUiState())
    val readerState: StateFlow<MyBooksNotesUiState> = _readerState.asStateFlow()

    /** 详情页信息栏:本地副本与服务器元数据对比,不发网络请求。 */
    fun refresh(book: Book, meta: ServerBookMeta?) {
        if (!MyBooksConfig.isMyBooks(book)) return
        if (meta == null) {
            _uiState.value = _uiState.value.copy(loading = true)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true)
            val serverHas = meta.hasNotes
            val serverUpdatedAt = meta.notesUpdatedAt?.takeIf { it.isNotBlank() }
            val serverSize = meta.notesSize
            val local = withContext(Dispatchers.IO) {
                runCatching { syncManager.localNote(book.id) }.getOrNull()
            }
            val upToDate = local != null && run {
                if (serverUpdatedAt != null) local.serverUpdatedAt == serverUpdatedAt
                else !serverHas && local.size == serverSize
            }
            _uiState.value = _uiState.value.copy(
                loading = false,
                localHas = local != null,
                localSize = local?.size ?: 0L,
                serverHas = serverHas,
                serverUpdatedAt = serverUpdatedAt,
                serverSize = serverSize,
                upToDate = upToDate
            )
        }
    }

    /** 阅读页:只读本地 .md。 */
    fun loadLocal(bookId: String) {
        viewModelScope.launch {
            _readerState.value = _readerState.value.copy(loading = true, error = null)
            val local = withContext(Dispatchers.IO) {
                runCatching { syncManager.localNote(bookId) }.getOrNull()
            }
            if (local == null) {
                _readerState.value = MyBooksNotesUiState(
                    loading = false,
                    error = context.getString(R.string.mybooks_notes_empty_title)
                )
                return@launch
            }
            val html = withContext(Dispatchers.Default) {
                runCatching { MarkdownRenderer.render(local.content).html }.getOrNull()
            }
            _readerState.value = MyBooksNotesUiState(
                loading = false,
                localHas = true,
                localSize = local.size,
                bodyHtml = html,
                error = if (html == null) context.getString(R.string.mybooks_notes_load_failed) else null
            )
        }
    }

    /**
     * 详情页“同步并阅读”:先下载到本地,再交给调用方打开阅读页。
     * 下载失败但本地有旧文件时回退到旧文件。
     */
    fun syncAndRead(bookId: String, onReady: (fallback: Boolean) -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncing = true, error = null, errorWasUpload = false)
            val localBefore = withContext(Dispatchers.IO) {
                runCatching { syncManager.localNote(bookId) }.getOrNull()
            }
            val downloaded = runCatching { syncManager.syncNoteToLocal(bookId) }
            val local = downloaded.getOrNull()
            if (local != null) {
                val serverHadNotes = _uiState.value.serverHas
                _uiState.value = _uiState.value.copy(
                    syncing = false,
                    localHas = true,
                    localSize = local.size,
                    serverUpdatedAt = local.serverUpdatedAt ?: _uiState.value.serverUpdatedAt,
                    upToDate = serverHadNotes && local.serverUpdatedAt != null
                )
                onReady(localBefore != null && downloaded.isFailure)
            } else {
                _uiState.value = _uiState.value.copy(
                    syncing = false,
                    error = context.getString(R.string.mybooks_notes_sync_failed_no_local),
                    errorWasUpload = false
                )
            }
        }
    }

    fun readLocal(bookId: String, onReady: () -> Unit) {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) {
                runCatching { syncManager.localNote(bookId) }.getOrNull()
            }
            if (local != null) {
                onReady()
            } else {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.mybooks_notes_sync_failed_no_local),
                    errorWasUpload = false
                )
            }
        }
    }

    /** 选择文件后先校验,供替换确认弹窗展示文件名/大小。 */
    fun pickNote(uri: Uri, onResult: (MyBooksPickedNote?) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readPickedNote(uri) }
            if (result == null) {
                _uiState.value = _uiState.value.copy(
                    error = context.getString(R.string.mybooks_notes_pick_invalid),
                    errorWasUpload = true
                )
            }
            onResult(result)
        }
    }

    fun upload(
        bookId: String,
        note: MyBooksPickedNote,
        expectedUpdatedAt: String?,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(uploading = true, error = null, errorWasUpload = false)
            val result = runCatching {
                syncManager.uploadNote(bookId, note.content, expectedUpdatedAt)
            }
            result.fold(
                onSuccess = { payload ->
                    _uiState.value = _uiState.value.copy(
                        uploading = false,
                        localHas = true,
                        localSize = note.size,
                        serverHas = true,
                        serverUpdatedAt = payload.updatedAt ?: _uiState.value.serverUpdatedAt,
                        serverSize = payload.size,
                        upToDate = true
                    )
                    val message = context.getString(R.string.mybooks_notes_uploaded)
                    onResult(true, message)
                },
                onFailure = { error ->
                    val message = when ((error as? MyBooksHttpException)?.status) {
                        409 -> context.getString(R.string.mybooks_notes_upload_conflict)
                        401, 403 -> context.getString(R.string.mybooks_readonly)
                        429 -> context.getString(R.string.mybooks_sync_rate_limited)
                        else -> error.message ?: context.getString(R.string.mybooks_notes_upload_failed)
                    }
                    _uiState.value = _uiState.value.copy(
                        uploading = false,
                        error = message,
                        errorWasUpload = true
                    )
                    onResult(false, message)
                }
            )
        }
    }

    private fun readPickedNote(uri: Uri): MyBooksPickedNote? {
        val resolver = context.contentResolver
        var displayName = uri.lastPathSegment.orEmpty()
        var declaredSize = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                    }
                }
        }
        val name = displayName.substringAfterLast('/')
        if (!name.endsWith(".md", true) && !name.endsWith(".markdown", true)) return null
        if (declaredSize > MAX_NOTE_BYTES) return null
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_NOTE_BYTES) return null
        if (bytes.any { it == 0.toByte() }) return null
        val content = runCatching { decodeUtf8(bytes) }.getOrNull() ?: return null
        return MyBooksPickedNote(name, bytes.size.toLong(), content)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("not utf-8", error)
        }
    }

    private companion object {
        const val MAX_NOTE_BYTES = 5L * 1024 * 1024
    }
}
