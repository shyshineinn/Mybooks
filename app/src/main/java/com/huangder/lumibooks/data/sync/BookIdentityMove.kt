package com.huangder.lumibooks.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.huangder.lumibooks.data.local.copyBookScopedPreferences
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookEntity
import org.json.JSONArray

internal suspend fun moveLocalBookIdentity(db: AppDatabase, context: Context, sourceId: String, targetId: String,
    transform: (BookEntity) -> BookEntity): String {
    copyBookScopedPreferences(context, sourceId, targetId)
    return db.withTransaction {
        val original = requireNotNull(db.bookDao().getBookById(sourceId))
        check(db.bookDao().getBookById(targetId) == null)
        db.bookDao().insertBook(transform(original).copy(id = targetId))
        val sql = db.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (table in tables) {
            val quoted = "\"" + table.replace("\"", "\"\"") + "\""
            val related = sql.query("PRAGMA table_info(" + quoted + ")").use { cursor ->
                val column = cursor.getColumnIndexOrThrow("name"); var found = false
                while (cursor.moveToNext()) if (cursor.getString(column) == "bookId") found = true
                found
            }
            if (related) sql.execSQL("UPDATE " + quoted + " SET bookId=? WHERE bookId=?", arrayOf(targetId, sourceId))
        }
        val previews = sql.query("SELECT id,previewBookIds FROM folders WHERE previewBookIds IS NOT NULL").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        for ((folder, raw) in previews) {
            val list = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            var changed = false
            for (index in 0 until list.length()) if (list.optString(index) == sourceId) { list.put(index, targetId); changed = true }
            if (changed) db.folderDao().updateFolderPreviewIfChanged(folder, list.toString())
        }
        db.bookDao().deleteBookById(sourceId)
        targetId
    }
}
