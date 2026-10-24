package com.huangder.lumibooks.data.sync

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

/** 单次阅读记录，对应服务端 `read_logs` 数组里的 `{ start, finish }`。 */
data class ServerReadLog(
    val start: String?,
    val finish: String?
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
    val series: String?,
    val seriesIndex: Int,
    val publisher: String?,
    val pubYear: String?,
    val isbn: String?,
    val doubanId: String?,
    val startDate: String?,
    val finishDate: String?,
    val notesUrl: String?,
    val readCount: Int,
    val readLogs: List<ServerReadLog>,
    val identifiers: List<ServerIdentifier>,
    val customDisplay: Map<String, String>,
    val files: List<RemoteBookFile>,
    val hasNotes: Boolean = false,
    val notesSize: Long = 0L,
    val notesUpdatedAt: String? = null,
    val identifiersRaw: Map<String, String> = emptyMap(),
    val customRaw: Map<String, Any?> = emptyMap(),
    val updatedAt: String? = null
) {
    companion object {
        fun fromJson(json: String, remoteId: Long): ServerBookMeta {
            val obj = JSONObject(json)
            fun optText(key: String): String? = obj.optTextOrNull(key)

            val identifiers = buildList {
                val list = obj.optJSONArray("identifier_list")
                if (list != null && list.length() > 0) {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val idType = item.optTextOrNull("type")
                        val label = item.optTextOrNull("label")
                        val value = item.optTextOrNull("value")
                        val url = item.optTextOrNull("url")
                        if (idType == null && label == null && value == null && url == null) continue
                        add(ServerIdentifier(type = idType, label = label, value = value, url = url))
                    }
                } else {
                    // 兼容只有 identifiers 对象（type -> value）的旧响应。
                    val map = obj.optJSONObject("identifiers") ?: return@buildList
                    val keys = map.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = map.optTextOrNull(key) ?: continue
                        add(ServerIdentifier(type = key, label = null, value = value, url = null))
                    }
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
                                raw.optTextAt(i)?.let(::add)
                            }
                        }.joinToString("、")
                        else -> raw.toString()
                    }.trim()
                    if (display.isNotBlank() && display != "[]" && display != "null") {
                        put(key, display)
                    }
                }
            }

            val readLogs = buildList {
                val raw = obj.opt("read_logs") ?: return@buildList
                val array = when (raw) {
                    is JSONArray -> raw
                    is String -> runCatching { JSONArray(raw) }.getOrNull()
                    else -> null
                } ?: return@buildList
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val start = item.optTextOrNull("start")
                    val finish = item.optTextOrNull("finish")
                    if (start != null || finish != null) add(ServerReadLog(start, finish))
                }
            }

            val rating = obj.optDouble("rating", Double.NaN).takeUnless { it.isNaN() }

            val identifiersRaw = buildMap {
                val map = obj.optJSONObject("identifiers")
                if (map != null) {
                    val keys = map.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = map.optTextOrNull(key)
                        if (key.isNotBlank() && value != null) put(key, value)
                    }
                } else {
                    identifiers.forEach { id ->
                        val type = id.type
                        val value = id.value
                        if (!type.isNullOrBlank() && !value.isNullOrBlank()) put(type, value)
                    }
                }
            }

            val customRaw = buildMap<String, Any?> {
                val map = obj.optJSONObject("custom") ?: return@buildMap
                val keys = map.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = map.opt(key)) {
                        is JSONArray -> put(key, buildList {
                            for (i in 0 until value.length()) {
                                value.optTextAt(i)?.let(::add)
                            }
                        })
                        null, JSONObject.NULL -> Unit
                        else -> put(key, value.toString())
                    }
                }
            }

            return ServerBookMeta(
                remoteId = remoteId,
                title = obj.optTextOrEmpty("title").ifBlank { "未命名" },
                author = normalizeRemoteAuthor(obj.optTextOrEmpty("author")),
                status = obj.optTextOrEmpty("status"),
                summary = obj.optTextOrEmpty("summary"),
                tags = parseRemoteTags(obj.opt("tags")),
                rating = rating,
                seriesLabel = optText("series_label"),
                series = optText("series"),
                seriesIndex = obj.optInt("series_index", 0),
                publisher = optText("publisher"),
                pubYear = optText("pub_year"),
                isbn = optText("isbn"),
                doubanId = optText("douban_id"),
                startDate = optText("start_date"),
                finishDate = optText("finish_date"),
                notesUrl = optText("notes_url"),
                readCount = obj.optInt("read_count", 0),
                readLogs = readLogs,
                identifiers = identifiers,
                customDisplay = customDisplay,
                files = parseRemoteFiles(obj),
                hasNotes = obj.optBoolean("has_notes", false),
                notesSize = obj.optLong("notes_size", 0L),
                notesUpdatedAt = optText("notes_updated_at"),
                identifiersRaw = identifiersRaw,
                customRaw = customRaw,
                updatedAt = optText("updated_at")
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
            File(dir, "$remoteId.json").writeText(json, Charsets.UTF_8)
        }
    }

    fun read(remoteId: Long): String? = runCatching {
        val file = File(dir, "$remoteId.json")
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    /** 服务器标签同步状态：服务器标签名 -> 本地 tagId；null 表示从未同步过。 */
    fun readTagMap(remoteId: Long): Map<String, String>? = runCatching<Map<String, String>?> {
        val file = File(dir, "$remoteId.tags.json")
        if (!file.isFile) return@runCatching null
        val obj = JSONObject(file.readText(Charsets.UTF_8))
        buildMap<String, String> {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val tagId = obj.optString(key)
                if (key.isNotBlank() && tagId.isNotBlank()) put(key, tagId)
            }
        }
    }.getOrNull()

    fun writeTagMap(remoteId: Long, tagMap: Map<String, String>) {
        runCatching {
            dir.mkdirs()
            val obj = JSONObject()
            tagMap.forEach { (name, tagId) ->
                if (name.isNotBlank() && tagId.isNotBlank()) obj.put(name, tagId)
            }
            File(dir, "$remoteId.tags.json").writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    fun delete(remoteId: Long) {
        runCatching { File(dir, "$remoteId.json").delete() }
        runCatching { File(dir, "$remoteId.tags.json").delete() }
    }
}
