package com.helloac.mybooks.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 识别号条目，对应服务端 `identifiers` / `identifier_list`。 */
data class ServerIdentifier(
    val type: String?,
    val label: String?,
    val value: String?,
    val url: String?
)

/**
 * 服务器单本详情的展示元数据（只含书籍内容元数据，
 * 不含域名/来源/服务器时间戳/服务器统计等用户要求隐藏的信息）。
 */
data class ServerBookMeta(
    val remoteId: Long,
    val title: String,
    val author: String,
    val status: String,
    val summary: String,
    val tags: List<String>,
    val rating: Double?,
    val seriesLabel: String?,
    val publisher: String?,
    val pubYear: String?,
    val isbn: String?,
    val doubanId: String?,
    val startDate: String?,
    val finishDate: String?,
    val notesUrl: String?,
    val identifiers: List<ServerIdentifier>,
    val customDisplay: Map<String, String>,
    val files: List<RemoteBookFile>
) {
    companion object {
        fun fromJson(json: String, remoteId: Long): ServerBookMeta {
            val obj = JSONObject(json)
            fun optText(key: String): String? = obj.optString(key).takeIf { it.isNotBlank() }

            val tags = buildList {
                when (val value = obj.opt("tags")) {
                    is JSONArray -> for (i in 0 until value.length()) {
                        value.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                    is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add)
                    else -> Unit
                }
            }

            val identifiers = buildList {
                val array = obj.optJSONArray("identifiers")
                    ?: obj.optJSONArray("identifier_list")
                    ?: return@buildList
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val idType = item.optString("type").takeIf { it.isNotBlank() }
                    val label = item.optString("label").takeIf { it.isNotBlank() }
                    val value = item.optString("value").takeIf { it.isNotBlank() }
                    val url = item.optString("url").takeIf { it.isNotBlank() }
                    if (idType == null && label == null && value == null && url == null) continue
                    add(ServerIdentifier(type = idType, label = label, value = value, url = url))
                }
            }

            val customDisplay = buildMap {
                val custom = obj.optJSONObject("custom_display") ?: return@buildMap
                val keys = custom.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val raw = custom.opt(key) ?: continue
                    val display = when (raw) {
                        is JSONArray -> buildList {
                            for (i in 0 until raw.length()) {
                                raw.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }.joinToString("、")
                        else -> raw.toString()
                    }
                    if (display.isNotBlank()) put(key, display)
                }
            }

            val files = buildList {
                val array = obj.optJSONArray("files") ?: return@buildList
                for (i in 0 until array.length()) {
                    val file = array.optJSONObject(i) ?: continue
                    val format = file.optString("format").uppercase()
                    val path = file.optString("path")
                    if (format.isBlank() || path.isBlank()) continue
                    add(RemoteBookFile(format = format, path = path, size = file.optLong("size", 0L)))
                }
            }

            val rating = obj.optDouble("rating", Double.NaN).takeUnless { it.isNaN() }

            return ServerBookMeta(
                remoteId = remoteId,
                title = obj.optString("title").ifBlank { "未命名" },
                author = obj.optString("author").ifBlank { "未知作者" },
                status = obj.optString("status").ifBlank { "want" },
                summary = obj.optString("summary").orEmpty(),
                tags = tags,
                rating = rating,
                seriesLabel = optText("series_label"),
                publisher = optText("publisher"),
                pubYear = optText("pub_year"),
                isbn = optText("isbn"),
                doubanId = optText("douban_id"),
                startDate = optText("start_date"),
                finishDate = optText("finish_date"),
                notesUrl = optText("notes_url"),
                identifiers = identifiers,
                customDisplay = customDisplay,
                files = files
            )
        }
    }
}

/** 单本详情 JSON 的本地缓存（`filesDir/mybooks/meta/{remoteId}.json`）。 */
class MyBooksMetaCache(context: Context) {

    private val dir = File(context.filesDir, "mybooks/meta")

    fun write(remoteId: Long, json: String) {
        runCatching {
            dir.mkdirs()
            File(dir, "$remoteId.json").writeText(json)
        }
    }

    fun read(remoteId: Long): String? = runCatching {
        val file = File(dir, "$remoteId.json")
        if (file.isFile) file.readText() else null
    }.getOrNull()

    fun delete(remoteId: Long) {
        runCatching { File(dir, "$remoteId.json").delete() }
    }
}
