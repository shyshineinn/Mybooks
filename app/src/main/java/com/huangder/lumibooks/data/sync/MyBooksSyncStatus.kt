package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.local.dao.MyBooksSyncStateDao
import com.huangder.lumibooks.data.local.dao.SyncStateDao
import com.huangder.lumibooks.data.local.dao.TagDao
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import com.huangder.lumibooks.domain.repository.BookRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

enum class MyBooksSyncFailure { TRANSIENT, AUTH, PERMISSION, CONFLICT, OTHER }

internal class MyBooksPermissionRequired(message: String) : IllegalStateException(message)

internal fun classifyMyBooksSyncFailure(error: Throwable): MyBooksSyncFailure = when (error) {
    is MyBooksPermissionRequired -> MyBooksSyncFailure.PERMISSION
    is MyBooksMetadataConflict, is MyBooksReadingConflict -> MyBooksSyncFailure.CONFLICT
    is MyBooksHttpException -> when (error.status) {
        401, 403 -> MyBooksSyncFailure.AUTH
        409 -> MyBooksSyncFailure.CONFLICT
        408, 425, 429, in 500..599 -> MyBooksSyncFailure.TRANSIENT
        else -> MyBooksSyncFailure.OTHER
    }
    is SSLException -> MyBooksSyncFailure.OTHER
    is IOException -> MyBooksSyncFailure.TRANSIENT
    else -> MyBooksSyncFailure.OTHER
}

data class MyBooksSyncStatus(
    val lastSuccessAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastFinishedAt: Long = 0,
    val message: String = "",
    val failure: MyBooksSyncFailure? = null,
    val retryStopped: Boolean = false
) {
    internal fun toJson(): String = JSONObject().apply {
        put("lastAttemptAt", lastAttemptAt)
        put("lastFinishedAt", lastFinishedAt)
        put("message", message)
        put("failure", failure?.name)
        put("retryStopped", retryStopped)
    }.toString()

    companion object {
        internal fun fromJson(json: String?): MyBooksSyncStatus = runCatching {
            val root = JSONObject(json ?: "{}")
            MyBooksSyncStatus(
                lastAttemptAt = root.optLong("lastAttemptAt"),
                lastFinishedAt = root.optLong("lastFinishedAt"),
                message = root.optString("message"),
                failure = root.optString("failure").takeIf { it.isNotBlank() }
                    ?.let(MyBooksSyncFailure::valueOf),
                retryStopped = root.optBoolean("retryStopped")
            )
        }.getOrDefault(MyBooksSyncStatus())
    }
}

/** Durable operational state, separate from per-book revision/conflict baselines. */
@Singleton
class MyBooksSyncStateStore @Inject constructor(
    private val syncStateDao: SyncStateDao,
    myBooksSyncStateDao: MyBooksSyncStateDao,
    bookRepository: BookRepository,
    tagDao: TagDao,
    readingSync: MyBooksReadingSync
) {
    val status = combine(
        syncStateDao.observeValue(STATUS_KEY),
        syncStateDao.observeValue(MyBooksSyncManager.KEY_LAST_SYNC)
    ) { json, lastSuccess ->
        MyBooksSyncStatus.fromJson(json).copy(lastSuccessAt = lastSuccess?.toLongOrNull() ?: 0L)
    }

    // Count books/deletions, not individual fields; tag-only edits also belong to the outbox.
    private val metadataPending = combine(
        myBooksSyncStateDao.observeAll(), myBooksSyncStateDao.observePendingDeletes(),
        bookRepository.getAllBooks(), tagDao.getAllTags(), tagDao.getAllBookTagLinks()
    ) { states, deletes, books, tags, links ->
        val booksById = books.associateBy { it.id }
        val tagNames = tags.associate { it.id to it.name }
        val namesByBook = links.groupBy { it.bookId }.mapValues { (_, values) ->
            values.mapNotNull { tagNames[it.tagId]?.lowercase() }.toSet()
        }
        val pending = deletes.mapTo(mutableSetOf()) { it.bookId }
        for (state in states) {
            val base = runCatching { JSONObject(state.baseJson) }.getOrDefault(JSONObject())
            val book = booksById[state.bookId]
            val changed = state.coverDirty ||
                runCatching { JSONObject(state.overridesJson).length() > 0 }.getOrDefault(true) ||
                (book != null && (isLocalTitleAuthorEdit(book.title, base.optTextOrEmpty("title")) ||
                    isLocalTitleAuthorEdit(book.author, normalizeRemoteAuthor(base.optTextOrEmpty("author"))))) ||
                namesByBook[state.bookId].orEmpty() != runCatching {
                    val array = org.json.JSONArray(state.baseTagsJson)
                    (0 until array.length()).map { array.getString(it).lowercase() }.toSet()
                }.getOrDefault(emptySet())
            if (changed) pending += state.bookId
        }
        pending.toSet()
    }

    val readingChanges = readingSync.pendingFingerprints
    val automaticReadingChanges = combine(readingChanges, syncStateDao.observeValue(MyBooksSyncManager.KEY_READING_CAPABILITY)) { pending, supported ->
        if (supported == "false") emptyMap() else pending
    }.distinctUntilChanged()
    private val uploadPending = combine(syncStateDao.observePrefix("mybooks_upload:"),
        syncStateDao.observePrefix("mybooks_upload_error:"), bookRepository.getAllBooks()) { transfers, errors, books ->
        val ids = books.mapTo(mutableSetOf()) { it.id }
        (transfers + errors).mapNotNull { row -> runCatching {
            val root = JSONObject(row.value)
            root.optString("localBookId").ifBlank { root.optString("bookId") }.takeIf { it in ids }
        }.getOrNull() }.toSet()
    }
    val pendingCount = combine(metadataPending, readingChanges, uploadPending) { metadata, reading, uploads -> (metadata + reading.keys + uploads).size }

    suspend fun started(at: Long) {
        val old = status.first()
        save(old.copy(lastAttemptAt = at, message = "", failure = null, retryStopped = false))
    }

    suspend fun completed(result: MyBooksSyncResult) {
        val old = status.first()
        val next = old.copy(lastAttemptAt = result.attemptAt, lastFinishedAt = System.currentTimeMillis(),
            message = result.message, failure = result.failure, retryStopped = false)
        val updates = mutableListOf(SyncStateEntity(STATUS_KEY, next.toJson()))
        if (result.success) updates += SyncStateEntity(MyBooksSyncManager.KEY_LAST_SYNC, next.lastFinishedAt.toString())
        syncStateDao.putStates(updates)
    }

    suspend fun readingFailure(error: Exception) {
        val old = status.first()
        save(old.copy(message = error.message.orEmpty(), failure = classifyMyBooksSyncFailure(error)))
    }

    suspend fun retryExhausted(attemptAt: Long) {
        val old = status.first()
        if (old.lastAttemptAt == attemptAt && old.failure == MyBooksSyncFailure.TRANSIENT) {
            save(old.copy(retryStopped = true))
        }
    }

    suspend fun reset() = syncStateDao.putStates(listOf(
        SyncStateEntity(STATUS_KEY, MyBooksSyncStatus().toJson()),
        SyncStateEntity(MyBooksSyncManager.KEY_LAST_SYNC, "0")
    ))

    private suspend fun save(status: MyBooksSyncStatus) =
        syncStateDao.putState(SyncStateEntity(STATUS_KEY, status.toJson()))

    companion object { internal const val STATUS_KEY = "mybooks_sync_status" }
}
