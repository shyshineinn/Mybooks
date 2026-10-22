package com.helloac.mybooks.remote

import android.content.Context
import com.huangder.lumibooks.data.sync.BookDownloadState
import com.huangder.lumibooks.data.sync.CloudBookDownloadResult
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.util.FileUtils
import com.huangder.lumibooks.util.parser.BookParserFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一次服务器书库同步的结果。 */
data class MyBooksSyncResult(
    val message: String,
    val success: Boolean
)

/**
 * my-books 服务器书库 → 本地书架的只读同步与下载。
 *
 * - 同步：把服务器书写成 `isCloudOnly` 的本地书（封面缓存到本地），
 *   服务器删掉的书不会自动删本地；网络失败不动本地数据。
 * - 下载：流式下载正文到书库目录并 `markBookDownloaded`，
 *   阅读进度等始终只存在本地，不回传服务器。
 */
@Singleton
class MyBooksSyncManager @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationContext private val context: Context
) {
    private val client = MyBooksApiClient()
    private val metaCache = MyBooksMetaCache(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val syncMutex = Mutex()
    private var lastSyncAt = 0L

    private val _downloadStates = MutableStateFlow<Map<String, BookDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, BookDownloadState>> = _downloadStates.asStateFlow()

    /** 距上次成功同步不足 [maxAgeMs] 则跳过（启动节流）。 */
    suspend fun syncIfStale(maxAgeMs: Long = 10 * 60_000) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < maxAgeMs) return
        syncNow()
    }

    suspend fun syncNow(): MyBooksSyncResult = syncMutex.withLock {
        try {
            withContext(Dispatchers.IO) { runSyncNow() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MyBooksSyncResult(error.message ?: "无法连接服务器", false)
        }
    }

    private suspend fun runSyncNow(): MyBooksSyncResult {
        val remoteBooks = try {
            client.fetchBooks()
        } catch (error: Exception) {
            return MyBooksSyncResult(error.message ?: "无法连接服务器", false)
        }
        var added = 0
        var updated = 0
        var skipped = 0
        for (remote in remoteBooks) {
            try {
                when (syncOneBook(remote)) {
                    1 -> added++
                    2 -> updated++
                    else -> skipped++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                skipped++
            }
        }
        lastSyncAt = System.currentTimeMillis()
        return MyBooksSyncResult("已同步服务器书库（新增 $added · 更新 $updated）", true)
    }

    /**
     * 同步一本服务器书：不存在则入库占位，已存在则只更新元数据。
     * 服务器上的书即使没有电子书文件（纯书目）也会入库展示；
     * 格式无法确定时用 EPUB 占位，下载时会按详情里的真实文件修正。
     * 返回 0=跳过（详情拉取失败）、1=新增、2=更新。
     */
    private suspend fun syncOneBook(remote: RemoteBook): Int {
        val remoteId = remote.id
        val bookId = MyBooksConfig.BOOK_ID_PREFIX + remoteId
        val detailJson = try {
            client.fetchBookJson(remoteId)
        } catch (_: Exception) {
            return 0
        }
        metaCache.write(remoteId, detailJson)
        val detail = try {
            parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
        } catch (_: Exception) {
            return 0
        }
        val file = RemoteBookFormats.preferredFile(detail.files)
        val format = file?.format?.toBookFormatOrNull() ?: BookFormat.EPUB
        val now = System.currentTimeMillis()
        val coverPath = cacheCover(remoteId, detail.coverUrl)

        val existing = bookRepository.getBookById(bookId)
        return when {
            existing == null -> {
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
                        metadataUpdatedAt = now,
                        sourceUri = MyBooksConfig.SOURCE_URI_PREFIX + remoteId
                    )
                )
                1
            }
            existing.isCloudOnly -> {
                bookRepository.updateBookMetadata(
                    existing.copy(
                        title = detail.title,
                        author = detail.author,
                        coverPath = coverPath ?: existing.coverPath,
                        format = format,
                        remoteFileSize = file?.size ?: 0L
                    )
                )
                2
            }
            else -> {
                bookRepository.updateBookMetadata(
                    existing.copy(
                        title = detail.title,
                        author = detail.author,
                        coverPath = coverPath ?: existing.coverPath
                    )
                )
                2
            }
        }
    }

    private suspend fun cacheCover(remoteId: Long, coverUrl: String?): String? {
        if (coverUrl.isNullOrBlank()) return null
        val dest = File(FileUtils.getCoversDirectory(context), "mybooks_cover_$remoteId.jpg")
        if (dest.isFile && dest.length() > 0L) return dest.absolutePath
        return if (client.fetchCover(coverUrl, dest)) dest.absolutePath else null
    }

    /** 下载服务器书正文到本地书库，进度写入 [downloadStates]。 */
    suspend fun downloadBook(bookId: String): CloudBookDownloadResult {
        val remoteId = MyBooksConfig.remoteIdOf(bookId)
            ?: return CloudBookDownloadResult(null, "这本书不是服务器书", false)
        return try {
            val detailJson = client.fetchBookJson(remoteId)
            metaCache.write(remoteId, detailJson)
            val detail = parseRemoteBook(detailJson, MyBooksConfig.SERVER_URL)
            val file = RemoteBookFormats.preferredFile(detail.files)
                ?: return CloudBookDownloadResult(null, "服务器上没有可下载的文件", false)
            val format = file.format.toBookFormatOrNull()
                ?: return CloudBookDownloadResult(null, "暂不支持 ${file.format} 格式", false)
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
                error("无法把下载文件写入书库目录")
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
                ?: error("下载完成但无法读取本地记录")
            _downloadStates.value = _downloadStates.value + (bookId to BookDownloadState.Completed)
            scope.launch {
                delay(1_500)
                if (_downloadStates.value[bookId] == BookDownloadState.Completed) {
                    _downloadStates.value = _downloadStates.value - bookId
                }
            }
            CloudBookDownloadResult(updated, "下载完成", true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileUtils.getBooksDirectory(context).listFiles()
                ?.filter { it.name.startsWith("$bookId.") && it.name.endsWith(".part") }
                ?.forEach { it.delete() }
            _downloadStates.value = _downloadStates.value +
                (bookId to BookDownloadState.Failed(error.message ?: "下载失败"))
            CloudBookDownloadResult(null, error.message ?: "下载失败", false)
        }
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
}
