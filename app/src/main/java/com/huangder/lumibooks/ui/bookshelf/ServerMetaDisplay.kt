package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.data.sync.RemoteBookFormats
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.data.sync.ServerIdentifier
import com.huangder.lumibooks.data.sync.ServerReadLog
import java.util.Locale

internal data class ServerDetailRow(
    val label: String,
    val value: String,
    val url: String? = null
)

internal fun statusDisplay(status: String): String = when (status.trim().lowercase(Locale.ROOT)) {
    "want", "wish" -> "想读"
    "reading" -> "在读"
    "done", "finished", "read" -> "已读"
    else -> status.trim()
}

/** 服务端评分为 0–10 分制；0 / null / NaN 视为未评分。 */
internal fun ratingDisplay(rating: Double?): String? {
    val value = rating ?: return null
    if (!value.isFinite() || value <= 0.0) return null
    return String.format(Locale.US, "%.1f / 10", value.coerceIn(0.0, 10.0))
}

internal fun seriesDisplay(meta: ServerBookMeta): String? {
    meta.seriesLabel?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val series = meta.series?.takeIf { it.isNotBlank() }?.trim() ?: return null
    return if (meta.seriesIndex > 0) "$series #${meta.seriesIndex}" else series
}

internal fun publicationDisplay(meta: ServerBookMeta): String? {
    val text = listOfNotNull(
        meta.publisher?.takeIf { it.isNotBlank() }?.trim(),
        meta.pubYear?.takeIf { it.isNotBlank() }?.trim()
    ).joinToString(" · ")
    return text.takeIf { it.isNotBlank() }
}

/** 过滤单标点、空值等无意义简介。 */
internal fun hasMeaningfulSummary(summary: String): Boolean {
    val text = summary.trim()
    if (text.isEmpty()) return false
    if (text.lowercase(Locale.ROOT) in setOf("null", "undefined", "暂无", "无", "暂无简介", "无简介")) {
        return false
    }
    return text.count { it.isLetterOrDigit() } >= 2
}

internal fun isbnRow(meta: ServerBookMeta): ServerDetailRow? {
    val identifier = meta.identifiers.firstOrNull {
        it.type.equals("isbn", ignoreCase = true) || it.label.equals("ISBN", ignoreCase = true)
    }
    val value = meta.isbn?.takeIf { it.isNotBlank() }?.trim()
        ?: identifier?.value?.takeIf { it.isNotBlank() }?.trim()
        ?: return null
    return ServerDetailRow("ISBN", value, identifier?.url)
}

internal fun identifierRows(meta: ServerBookMeta): List<ServerDetailRow> {
    val seen = HashSet<String>()
    val rows = mutableListOf<ServerDetailRow>()
    meta.identifiers.forEach { identifier ->
        val type = identifier.type?.takeIf { it.isNotBlank() }?.trim()
        val label = identifier.label?.takeIf { it.isNotBlank() }?.trim() ?: type ?: return@forEach
        if (type.equals("isbn", ignoreCase = true) || label.equals("ISBN", ignoreCase = true)) {
            return@forEach
        }
        val value = identifier.value?.takeIf { it.isNotBlank() }?.trim() ?: return@forEach
        val key = "${label.lowercase(Locale.ROOT)}\u0000$value"
        if (seen.add(key)) rows += ServerDetailRow(label, value, identifier.url)
    }
    return rows
}

private val reservedCustomLabels = setOf(
    "书名", "作者", "文件夹", "标签", "简介", "阅读状态", "评分", "系列", "出版",
    "isbn", "笔记链接", "其他识别号", "阅读记录", "阅读日期", "阅读次数",
    "电子书文件", "文件名", "格式", "存储状态", "导入时间", "最后阅读", "文件位置"
)

internal fun customRows(meta: ServerBookMeta, reserved: Set<String> = emptySet()): List<ServerDetailRow> {
    val seen = (reservedCustomLabels + reserved)
        .mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
    return meta.customDisplay.mapNotNull { (rawLabel, rawValue) ->
        val label = rawLabel.trim()
        val value = rawValue.trim()
        if (label.isEmpty() || value.isEmpty()) return@mapNotNull null
        if (!seen.add(label.lowercase(Locale.ROOT))) return@mapNotNull null
        ServerDetailRow(label, value)
    }
}

/** 内容卡中的元数据行（简介单独渲染，阅读记录和文件行由各自的区块处理）。 */
internal fun buildServerDetailRows(meta: ServerBookMeta): List<ServerDetailRow> {
    val rows = mutableListOf<ServerDetailRow>()
    statusDisplay(meta.status).takeIf { it.isNotBlank() }?.let {
        rows += ServerDetailRow("阅读状态", it)
    }
    ratingDisplay(meta.rating)?.let { rows += ServerDetailRow("评分", it) }
    seriesDisplay(meta)?.let { rows += ServerDetailRow("系列", it) }
    publicationDisplay(meta)?.let { rows += ServerDetailRow("出版", it) }
    isbnRow(meta)?.let { rows += it }
    rows += identifierRows(meta)
    val reserved = buildSet {
        rows.forEach { add(it.label) }
        meta.identifiers.forEach { identifier ->
            identifier.label?.takeIf { it.isNotBlank() }?.let(::add)
            identifier.type?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
    rows += customRows(meta, reserved)
    return rows
}

internal fun sortedReadingLogs(meta: ServerBookMeta): List<ServerReadLog> {
    val seen = HashSet<String>()
    return meta.readLogs
        .filterNot { it.start.isNullOrBlank() && it.finish.isNullOrBlank() }
        .sortedWith(
            compareByDescending<ServerReadLog> { it.start.orEmpty() }
                .thenByDescending { it.finish.orEmpty() }
        )
        .filter { seen.add("${it.start.orEmpty()}\u0000${it.finish.orEmpty()}") }
}

internal fun readingLogLabels(meta: ServerBookMeta): List<String> =
    sortedReadingLogs(meta).mapNotNull { log ->
        val start = log.start?.takeIf { it.isNotBlank() }
        val finish = log.finish?.takeIf { it.isNotBlank() }
        when {
            start != null && finish != null -> "$start 至 $finish"
            start != null -> "$start · 进行中"
            finish != null -> "至 $finish"
            else -> null
        }
    }

internal fun readCountLabel(meta: ServerBookMeta): String? =
    meta.readCount.takeIf { it > 0 }?.let { "共 $it 次" }

internal fun readingDateLabel(meta: ServerBookMeta): String? {
    val parts = listOfNotNull(
        meta.startDate?.takeIf { it.isNotBlank() }?.let { "开始 $it" },
        meta.finishDate?.takeIf { it.isNotBlank() }?.let { "读完 $it" }
    )
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

internal fun fileRowValue(meta: ServerBookMeta, isCloudOnly: Boolean): String? {
    val files = meta.files
        .filter { RemoteBookFormats.isSupported(it.format) }
        .distinctBy { it.format.uppercase(Locale.ROOT) }
        .sortedBy { RemoteBookFormats.preferredOrderIndex(it.format) }
    if (files.isNotEmpty()) {
        return files.joinToString(" · ") { file ->
            listOf(RemoteBookFormats.displayFormat(file.format), formatServerFileSize(file.size))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }
    if (!isCloudOnly) return null
    return if (meta.files.isEmpty()) "暂无电子书文件" else "暂无可阅读格式"
}

internal fun formatServerFileSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        else -> String.format(Locale.US, "%.0f KB", bytes / kb)
    }
}
