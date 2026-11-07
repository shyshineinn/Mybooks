package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.RemoteBookFormats
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.data.sync.ServerIdentifier
import com.huangder.lumibooks.data.sync.ServerReadLog
import com.huangder.lumibooks.domain.model.ReadingStatus
import java.util.Locale

internal data class ServerDetailRow(
    val label: String,
    val value: String,
    val url: String? = null,
    val isStatus: Boolean = false
)

@Composable
internal fun statusDisplay(status: String): String =
    when (ReadingStatus.fromServerStatus(status)) {
        ReadingStatus.WISH -> stringResource(R.string.reading_status_wish)
        ReadingStatus.WANT_TO_READ -> stringResource(R.string.mybooks_server_wants)
        ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
        ReadingStatus.DONE -> stringResource(R.string.reading_status_done)
        null -> status.trim()
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

internal fun identifierRows(
    meta: ServerBookMeta,
    labelFor: (String) -> String = { it }
): List<ServerDetailRow> {
    val seen = HashSet<String>()
    val rows = mutableListOf<ServerDetailRow>()
    meta.identifiers.forEach { identifier ->
        val type = identifier.type?.takeIf { it.isNotBlank() }?.trim()
        val rawLabel = identifier.label?.takeIf { it.isNotBlank() }?.trim() ?: type ?: return@forEach
        if (type.equals("isbn", ignoreCase = true) || rawLabel.equals("ISBN", ignoreCase = true)) {
            return@forEach
        }
        val value = identifier.value?.takeIf { it.isNotBlank() }?.trim() ?: return@forEach
        val label = labelFor(rawLabel)
        val key = "${label.lowercase(Locale.ROOT)}\u0000$value"
        if (seen.add(key)) rows += ServerDetailRow(label, value, identifier.url)
    }
    return rows
}

/**
 * 内容卡中的元数据行（简介单独渲染，阅读记录和文件行由各自的区块处理）。
 * [statusText] 传入详情页当前有效状态（含本地 override），避免等待缓存刷新。
 */
@Composable
internal fun buildServerDetailRows(
    meta: ServerBookMeta,
    statusText: String? = null
): List<ServerDetailRow> {
    val doubanLabel = stringResource(R.string.server_meta_douban)
    val labelFor: (String) -> String = { raw ->
        if (raw.equals("douban", ignoreCase = true) || raw.equals("douban_id", ignoreCase = true)) {
            doubanLabel
        } else {
            raw
        }
    }
    val rows = mutableListOf<ServerDetailRow>()
    meta.translators.takeIf { it.isNotEmpty() }?.let {
        rows += ServerDetailRow(stringResource(R.string.mybooks_edit_field_translators), it.joinToString("、"))
    }
    (statusText ?: statusDisplay(meta.status)).takeIf { it.isNotBlank() }?.let {
        rows += ServerDetailRow(stringResource(R.string.server_meta_status), it, isStatus = true)
    }
    if (meta.serverFavorite) {
        rows += ServerDetailRow(stringResource(R.string.mybooks_server_marks), stringResource(R.string.favorite))
    }
    ratingDisplay(meta.rating)?.let {
        rows += ServerDetailRow(stringResource(R.string.server_meta_rating), it)
    }
    seriesDisplay(meta)?.let {
        rows += ServerDetailRow(stringResource(R.string.server_meta_series), it)
    }
    publicationDisplay(meta)?.let {
        rows += ServerDetailRow(stringResource(R.string.server_meta_publication), it)
    }
    isbnRow(meta)?.let { rows += it }
    rows += identifierRows(meta, labelFor)
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

@Composable
internal fun readingLogLabels(meta: ServerBookMeta): List<String> =
    sortedReadingLogs(meta).mapNotNull { log ->
        val start = log.start?.takeIf { it.isNotBlank() }
        val finish = log.finish?.takeIf { it.isNotBlank() }
        when {
            start != null && finish != null -> stringResource(R.string.server_meta_read_range, start, finish)
            start != null -> stringResource(R.string.server_meta_reading_in_progress, start)
            finish != null -> stringResource(R.string.server_meta_until, finish)
            else -> null
        }
    }

@Composable
internal fun readCountLabel(meta: ServerBookMeta): String? =
    meta.readCount.takeIf { it > 0 }?.let {
        stringResource(R.string.server_meta_read_count_value, it)
    }

@Composable
internal fun readingDateLabel(meta: ServerBookMeta): String? {
    val parts = listOfNotNull(
        meta.startDate?.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.server_meta_start_date, it)
        },
        meta.finishDate?.takeIf { it.isNotBlank() }?.let {
            stringResource(R.string.server_meta_finish_date, it)
        }
    )
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

@Composable
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
    return if (meta.files.isEmpty()) {
        stringResource(R.string.server_meta_no_files)
    } else {
        stringResource(R.string.server_meta_no_supported_format)
    }
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
