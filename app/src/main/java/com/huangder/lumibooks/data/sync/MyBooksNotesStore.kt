package com.huangder.lumibooks.data.sync

import android.content.Context
import org.json.JSONObject
import java.io.File

data class MyBooksLocalNote(
    val content: String,
    val serverUpdatedAt: String?,
    val size: Long
)

/**
 * 服务器读书心得的本地阅读副本。阅读页只读这里的 .md,
 * 不从网络直接渲染。
 */
class MyBooksNotesStore(context: Context) {
    private val dir = File(context.filesDir, "mybooks/notes")

    private fun markdownFile(remoteId: Long) = File(dir, "$remoteId.md")
    private fun metaFile(remoteId: Long) = File(dir, "$remoteId.json")

    fun read(remoteId: Long): MyBooksLocalNote? {
        val file = markdownFile(remoteId)
        if (!file.isFile) return null
        val meta = runCatching { JSONObject(metaFile(remoteId).readText()) }.getOrNull()
        val content = file.readText()
        return MyBooksLocalNote(
            content = content,
            serverUpdatedAt = meta?.optTextOrNull("serverUpdatedAt"),
            size = meta?.optLong("size", content.toByteArray().size.toLong()) ?: content.toByteArray().size.toLong()
        )
    }

    fun hasLocal(remoteId: Long): Boolean = markdownFile(remoteId).isFile

    fun isCurrent(remoteId: Long, serverUpdatedAt: String?, serverSize: Long): Boolean {
        val local = read(remoteId) ?: return false
        if (serverUpdatedAt != null && local.serverUpdatedAt == serverUpdatedAt) return true
        return serverUpdatedAt == null && local.size == serverSize
    }

    fun write(remoteId: Long, content: String, serverUpdatedAt: String?, size: Long) {
        dir.mkdirs()
        val file = markdownFile(remoteId)
        val temp = File(dir, "$remoteId.md.tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                error("无法写入本地心得")
            }
        }
        metaFile(remoteId).writeText(
            JSONObject()
                .put("serverUpdatedAt", serverUpdatedAt ?: "")
                .put("size", size)
                .put("localMtime", System.currentTimeMillis())
                .toString()
        )
    }

    fun delete(remoteId: Long) {
        markdownFile(remoteId).delete()
        metaFile(remoteId).delete()
    }
}
