package com.helloac.mybooks.remote

import org.json.JSONArray
import org.json.JSONObject

/** 电子书文件条目，对应 my-books `book_files` 表。 */
data class RemoteBookFile(
    val format: String,
    val path: String,
    val size: Long
)

/**
 * my-books `/api/books` 返回的单本书（只取 Android 端需要的字段）。
 * 阅读进度、笔记等全部留在本地，不参与任何回传。
 */
data class RemoteBook(
    val id: Long,
    val title: String,
    val author: String,
    val status: String,
    val summary: String,
    val tags: List<String>,
    val coverUrl: String?,
    val rating: Double?,
    val seriesLabel: String?,
    val files: List<RemoteBookFile>
) {
    fun fileFor(format: String): RemoteBookFile? =
        files.firstOrNull { it.format.equals(format, ignoreCase = true) }
}

object RemoteBookFormats {
    /** Lumi 的阅读器支持这些格式；AZW3 暂不支持。 */
    val supported = setOf("EPUB", "PDF", "TXT", "MOBI", "CBZ", "MD", "MARKDOWN")

    private val preferredOrder = listOf("EPUB", "PDF", "MOBI", "TXT", "CBZ", "MD")

    fun isSupported(format: String): Boolean =
        format.uppercase() in supported

    fun preferredFile(files: List<RemoteBookFile>): RemoteBookFile? =
        files.filter { isSupported(it.format) }
            .minByOrNull { file ->
                preferredOrder.indexOf(file.format.uppercase()).takeIf { it >= 0 } ?: preferredOrder.size
            }

    fun displayFormat(format: String): String =
        if (format.equals("MARKDOWN", true) || format.equals("MD", true)) "MD" else format.uppercase()
}

internal fun parseRemoteBooks(json: String, baseUrl: String): List<RemoteBook> {
    val array = JSONArray(json)
    val books = ArrayList<RemoteBook>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        books += item.toRemoteBook(baseUrl)
    }
    return books
}

/** 解析 `GET /api/books/{id}` 的单本详情 JSON。 */
internal fun parseRemoteBook(json: String, baseUrl: String): RemoteBook =
    JSONObject(json).toRemoteBook(baseUrl)

private fun JSONObject.toRemoteBook(baseUrl: String): RemoteBook {
    val files = optJSONArray("files")?.let { array ->
        buildList {
            for (i in 0 until array.length()) {
                val file = array.optJSONObject(i) ?: continue
                val format = file.optString("format").uppercase()
                val path = file.optString("path")
                if (format.isBlank() || path.isBlank()) continue
                add(RemoteBookFile(format = format, path = path, size = file.optLong("size", 0L)))
            }
        }
    }.orEmpty()

    val summary = optString("summary").orEmpty()
    val rawCover = optString("cover").takeIf { it.isNotBlank() }
    val rating = optDouble("rating", Double.NaN).takeUnless { it.isNaN() }

    return RemoteBook(
        id = optLong("id", 0L),
        title = optString("title").ifBlank { "未命名" },
        author = optString("author").ifBlank { "未知作者" },
        status = optString("status").ifBlank { "want" },
        summary = summary,
        tags = parseTags(opt("tags")),
        coverUrl = rawCover?.let { resolveUrl(baseUrl, it) },
        rating = rating,
        seriesLabel = optString("series_label").takeIf { it.isNotBlank() },
        files = files
    )
}

private fun parseTags(value: Any?): List<String> {
    return when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) {
                value.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        is String -> runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrElse {
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        else -> emptyList()
    }
}

internal fun resolveUrl(baseUrl: String, path: String): String {
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = baseUrl.trim().trimEnd('/')
    return if (path.startsWith('/')) "$base$path" else "$base/$path"
}
