package com.huangder.lumibooks.data.sync

import androidx.room.withTransaction
import com.huangder.lumibooks.data.local.dao.FolderDao
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.FolderEntity
import com.huangder.lumibooks.data.local.entity.BookFolderCrossRefEntity
import com.huangder.lumibooks.domain.model.FolderNameValidator
import java.util.UUID

private val directorySegment = Regex("[\\p{L}\\p{N}]+")

internal fun myBooksDirectorySegments(path: String): List<String>? {
    if (path.isEmpty()) return emptyList()
    if (path.equals("clear", ignoreCase = true) || path == "清除") return null
    return path.split('.').takeIf { parts ->
        parts.size <= 2 && parts.all {
            it.codePointCount(0, it.length) in 1..24 && directorySegment.matches(it)
        }
    }
}

internal val FolderEntity.isStorageFolder: Boolean
    get() = storageTreeUri != null || storageDocumentUri != null || storageParentUri != null

internal fun myBooksFolderUsesStorage(folderId: String, find: (String) -> FolderEntity?): Boolean {
    var folder = find(folderId)
    val visited = mutableSetOf<String>()
    while (folder != null && visited.add(folder.id)) {
        if (folder.isStorageFolder) return true
        folder = folder.parentId?.let(find)
    }
    return false
}

/** Null means the local folder is outside the service's virtual directory model. */
internal suspend fun FolderDao.myBooksDirectory(bookId: String): String? {
    return myBooksDirectoryForFolder(getBookFolderId(bookId), ::getFolderById)
}

internal suspend fun myBooksDirectoryForFolder(folderId: String?, find: suspend (String) -> FolderEntity?): String? {
    var currentId = folderId
    val parts = mutableListOf<String>()
    while (currentId != null) {
        if (parts.size == 2) return null
        val folder = find(currentId) ?: return null
        if (folder.isStorageFolder) return null
        if (!directorySegment.matches(folder.name)) return null
        parts += folder.name
        currentId = folder.parentId
    }
    val path = parts.asReversed().joinToString(".")
    return path.takeIf { myBooksDirectorySegments(it) != null }
}

internal suspend fun AppDatabase.applyMyBooksDirectory(bookId: String, expected: String, remote: String) {
    if (expected.equals(remote, ignoreCase = true)) return
    val parts = myBooksDirectorySegments(remote) ?: error("目录名无效")
    withTransaction {
        val dao = folderDao()
        if (dao.myBooksDirectory(bookId) != expected) return@withTransaction
        var parent: String? = null
        for (name in parts) {
            val normalized = FolderNameValidator.normalized(name)
            val existing = dao.getFolderByNormalizedName(normalized, parent)
            check(existing?.isStorageFolder != true) { "目录与本机文件夹重名" }
            val folder = existing ?: FolderEntity(
                UUID.randomUUID().toString(), name, normalized, parent, System.currentTimeMillis(), updatedAt = 0
            ).also { dao.insertFolder(it) }
            parent = folder.id
        }
        // A native directory read must not outrank a user-edited App layout.
        val target = parent
        if (target == null) dao.moveBooks(setOf(bookId), null)
        else dao.upsertBookFolderLinks(listOf(BookFolderCrossRefEntity(bookId, target, updatedAt = 0)))
    }
}
