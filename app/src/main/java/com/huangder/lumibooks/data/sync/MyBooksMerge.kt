package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.domain.model.Book
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

enum class MyBooksCoverAction {
    NONE,
    UPLOAD_LOCAL,
    DOWNLOAD_REMOTE
}

data class MyBooksMergeResult(
    /** 本地胜出的字段,需 PATCH;键为服务器字段名。 */
    val patch: JSONObject,
    /** 服务器胜出/已收敛的 override key。 */
    val dropOverrides: Set<String>,
    /** 本地胜出的书籍字段,应用服务器字段时要跳过。 */
    val localWonFields: Set<String>,
    /** 三方合并后的标签集合。 */
    val mergedTags: List<String>,
    val tagsDirty: Boolean,
    val coverAction: MyBooksCoverAction,
    val remoteCoverUrl: String?,
    val conflicts: List<String>
) {
    val hasLocalChanges: Boolean
        get() = patch.length() > 0 || tagsDirty || coverAction == MyBooksCoverAction.UPLOAD_LOCAL
}

/**
 * 纯函数三方合并:
 * base   = 上次同步的服务器快照
 * local  = 本地当前值(Book / overrides / 标签表)
 * remote = 本次服务器详情
 *
 * 同一字段双方都改时按 editedAt 与服务器 updated_at 取较晚者;
 * 本地未编辑过的字段直接采用服务器值。
 */
object MyBooksMerge {
    private val scalarFields = listOf(
        "title", "author", "status", "summary", "rating",
        "series", "series_index", "publisher", "pub_year",
        "isbn", "douban_id", "notes_url"
    )

    fun merge(
        book: Book,
        baseJson: String,
        remoteJson: String,
        overridesJson: String,
        editedAtJson: String,
        baseTags: List<String>,
        localTags: List<String>,
        coverDirty: Boolean,
        coverEditedAt: Long
    ): MyBooksMergeResult {
        val base = runCatching { JSONObject(baseJson) }.getOrDefault(JSONObject())
        val remote = runCatching { JSONObject(remoteJson) }.getOrDefault(JSONObject())
        val overrides = runCatching { JSONObject(overridesJson) }.getOrDefault(JSONObject())
        val editedAt = runCatching { JSONObject(editedAtJson) }.getOrDefault(JSONObject())
        val remoteEpoch = parseEpoch(remote.optString("updated_at"))
        val patch = JSONObject()
        val drop = linkedSetOf<String>()
        val localWon = linkedSetOf<String>()
        val conflicts = mutableListOf<String>()

        for (field in scalarFields) {
            val baseValue = normalizeScalar(field, base.opt(field))
            val remoteValue = normalizeScalar(field, remote.opt(field))
            val localValue = when (field) {
                // 有 override 说明用户明确改过书名/作者，按用户输入提交（可能是空串）；
                // 没有 override 时本地留空按基线处理，避免把空值当成修改推给服务器。
                "title" -> if (overrides.has(field)) overrides.opt(field) else book.title.ifBlank { baseValue }
                "author" -> if (overrides.has(field)) overrides.opt(field) else book.author.ifBlank { baseValue }
                "notes_url" -> baseValue // App 不编辑心得链接
                else -> if (overrides.has(field)) overrides.opt(field) else baseValue
            }
            val localChanged = !valueEquals(localValue, baseValue)
            val remoteChanged = !valueEquals(remoteValue, baseValue)
            if (!localChanged) {
                if (overrides.has(field)) drop += field
                continue
            }
            if (!remoteChanged) {
                if (!valueEquals(localValue, remoteValue)) {
                    patch.put(field, localValue)
                    localWon += field
                } else {
                    drop += field
                }
                continue
            }
            if (valueEquals(localValue, remoteValue)) {
                drop += field
                continue
            }
            conflicts += field
            val localEpoch = editedAt.optLong(field, 0L)
            if (localEpoch > remoteEpoch) {
                patch.put(field, localValue)
                localWon += field
            } else {
                drop += field
            }
        }

        mergeIdentifiers(base, remote, overrides, editedAt, remoteEpoch, patch, drop)
        mergeCustom(base, remote, overrides, editedAt, remoteEpoch, patch, drop)
        val readLogsOverride = mergeReadLogs(base, remote, overrides, editedAt, remoteEpoch, patch, drop)

        val remoteTags = parseRemoteTags(remote.opt("tags"))
        val mergedTags = mergeTags(baseTags, localTags, remoteTags)
        val tagsDirty = !tagSetEquals(mergedTags, remoteTags)

        val baseCover = base.optTextOrNull("cover")
        val remoteCover = remote.optTextOrNull("cover")
        val remoteCoverChanged = baseCover != remoteCover
        val coverAction = when {
            coverDirty && !remoteCoverChanged -> MyBooksCoverAction.UPLOAD_LOCAL
            coverDirty && remoteCoverChanged -> {
                if (coverEditedAt > remoteEpoch) MyBooksCoverAction.UPLOAD_LOCAL
                else if (remoteCover != null) MyBooksCoverAction.DOWNLOAD_REMOTE
                else MyBooksCoverAction.NONE
            }
            remoteCoverChanged && remoteCover != null -> MyBooksCoverAction.DOWNLOAD_REMOTE
            else -> MyBooksCoverAction.NONE
        }

        // readLogsOverride 只用于标记 override 是否仍在;值已进入 patch/drop。
        if (readLogsOverride == null && overrides.has("read_logs")) drop += "read_logs"

        return MyBooksMergeResult(
            patch = patch,
            dropOverrides = drop,
            localWonFields = localWon,
            mergedTags = mergedTags,
            tagsDirty = tagsDirty,
            coverAction = coverAction,
            remoteCoverUrl = remoteCover,
            conflicts = conflicts
        )
    }

    private fun mergeIdentifiers(
        base: JSONObject,
        remote: JSONObject,
        overrides: JSONObject,
        editedAt: JSONObject,
        remoteEpoch: Long,
        patch: JSONObject,
        drop: MutableSet<String>
    ) {
        val baseMap = base.optJSONObject("identifiers").orEmptyMap()
        val remoteMap = remote.optJSONObject("identifiers").orEmptyMap()
        val types = LinkedHashSet<String>()
        types.addAll(baseMap.keys)
        types.addAll(remoteMap.keys)
        overrides.keys().forEach { key ->
            if (key.startsWith("identifiers.")) types += key.removePrefix("identifiers.")
        }
        val patchIds = JSONObject()
        for (type in types) {
            val normalized = type.lowercase(Locale.ROOT)
            if (normalized == "isbn" || normalized == "douban" || normalized == "douban_id") continue
            val key = MyBooksConfig.identifierOverrideKey(type)
            val baseValue = baseMap[type].orEmpty()
            val remoteValue = remoteMap[type].orEmpty()
            val localValue = if (overrides.has(key)) overrides.optTextOrEmpty(key) else baseValue
            val localChanged = localValue != baseValue
            val remoteChanged = remoteValue != baseValue
            if (!localChanged) {
                if (overrides.has(key)) drop += key
                continue
            }
            if (!remoteChanged) {
                if (localValue != remoteValue) patchIds.put(type, localValue)
                else drop += key
                continue
            }
            if (localValue == remoteValue) {
                drop += key
                continue
            }
            if (editedAt.optLong(key, 0L) > remoteEpoch) patchIds.put(type, localValue)
            else drop += key
        }
        if (patchIds.length() > 0) patch.put("identifiers", patchIds)
    }

    private fun mergeCustom(
        base: JSONObject,
        remote: JSONObject,
        overrides: JSONObject,
        editedAt: JSONObject,
        remoteEpoch: Long,
        patch: JSONObject,
        drop: MutableSet<String>
    ) {
        val baseMap = base.optJSONObject("custom").orEmptyAnyMap()
        val remoteMap = remote.optJSONObject("custom").orEmptyAnyMap()
        val labels = LinkedHashSet<String>()
        labels += baseMap.keys
        labels += remoteMap.keys
        overrides.keys().forEach { key ->
            if (key.startsWith("custom.")) labels += key.removePrefix("custom.")
        }
        val patchCustom = JSONObject()
        for (label in labels) {
            val key = MyBooksConfig.customOverrideKey(label)
            val baseValue = baseMap[label]
            val remoteValue = remoteMap[label]
            val localValue = if (overrides.has(key)) overrides.opt(key) else baseValue
            val localChanged = !customValueEquals(localValue, baseValue)
            val remoteChanged = !customValueEquals(remoteValue, baseValue)
            if (!localChanged) {
                if (overrides.has(key)) drop += key
                continue
            }
            if (!remoteChanged) {
                if (!customValueEquals(localValue, remoteValue)) patchCustom.put(label, localValue ?: "")
                else drop += key
                continue
            }
            if (customValueEquals(localValue, remoteValue)) {
                drop += key
                continue
            }
            if (editedAt.optLong(key, 0L) > remoteEpoch) patchCustom.put(label, localValue ?: "")
            else drop += key
        }
        if (patchCustom.length() > 0) patch.put("custom", patchCustom)
    }

    /** 返回 null 表示没有有效 override;否则返回本地阅读记录是否已写入 patch。 */
    private fun mergeReadLogs(
        base: JSONObject,
        remote: JSONObject,
        overrides: JSONObject,
        editedAt: JSONObject,
        remoteEpoch: Long,
        patch: JSONObject,
        drop: MutableSet<String>
    ): Boolean? {
        if (!overrides.has("read_logs")) return null
        val baseLogs = canonicalLogs(base.opt("read_logs"))
        val remoteLogs = canonicalLogs(remote.opt("read_logs"))
        val localLogs = canonicalLogs(overrides.opt("read_logs"))
        val localChanged = localLogs != baseLogs
        val remoteChanged = remoteLogs != baseLogs
        if (!localChanged) {
            drop += "read_logs"
            return null
        }
        if (!remoteChanged) {
            if (localLogs != remoteLogs) {
                patch.put("read_logs", logsToJson(localLogs))
                patch.put("start_date", "")
                patch.put("finish_date", "")
                patch.put("read_count", 0)
            } else {
                drop += "read_logs"
            }
            return true
        }
        if (localLogs == remoteLogs) {
            drop += "read_logs"
            return null
        }
        return if (editedAt.optLong("read_logs", 0L) > remoteEpoch) {
            patch.put("read_logs", logsToJson(localLogs))
            patch.put("start_date", "")
            patch.put("finish_date", "")
            patch.put("read_count", 0)
            true
        } else {
            drop += "read_logs"
            null
        }
    }

    private fun JSONObject?.orEmptyMap(): Map<String, String> {
        val obj = this ?: return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optTextOrNull(key)
                if (value != null) put(key, value)
            }
        }
    }

    private fun JSONObject?.orEmptyAnyMap(): Map<String, Any?> {
        val obj = this ?: return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                if (value != null && value != JSONObject.NULL) put(key, value)
            }
        }
    }

    private fun normalizeScalar(field: String, value: Any?): Any? = when (field) {
        "title" -> (value as? String).orEmpty().ifBlank { "未命名" }
        "author" -> normalizeRemoteAuthor(value as? String)
        else -> value
    }

    private fun parseEpoch(value: String?): Long =
        value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun valueEquals(a: Any?, b: Any?): Boolean {
        if (a == null || a == JSONObject.NULL) return b == null || b == JSONObject.NULL
        if (b == null || b == JSONObject.NULL) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is JSONArray && b is JSONArray) return a.toString() == b.toString()
        if (a is JSONObject && b is JSONObject) return a.toString() == b.toString()
        return a.toString() == b.toString()
    }

    private fun customValueEquals(a: Any?, b: Any?): Boolean {
        if (a == null || a == JSONObject.NULL) return b == null || b == JSONObject.NULL
        if (b == null || b == JSONObject.NULL) return false
        if (a is JSONArray && b is JSONArray) {
            val left = (0 until a.length()).mapNotNull { a.optTextAt(it) }.sorted()
            val right = (0 until b.length()).mapNotNull { b.optTextAt(it) }.sorted()
            return left == right
        }
        return a.toString() == b.toString()
    }

    private fun canonicalLogs(raw: Any?): List<Pair<String, String>> {
        val array = when (raw) {
            is JSONArray -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val start = obj.optTextOrEmpty("start").trim()
            val finish = obj.optTextOrEmpty("finish").trim()
            if (start.isEmpty() && finish.isEmpty()) continue
            out += start to finish
        }
        return out.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun logsToJson(logs: List<Pair<String, String>>): JSONArray {
        val array = JSONArray()
        for ((start, finish) in logs) {
            array.put(JSONObject().put("start", start).put("finish", finish))
        }
        return array
    }

    private fun mergeTags(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val baseKeys = base.map { it.lowercase(Locale.ROOT) }.toSet()
        val localKeys = local.map { it.lowercase(Locale.ROOT) }.toSet()
        val localRemoved = baseKeys - localKeys
        val localAdded = localKeys - baseKeys
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (tag in remote) {
            val key = tag.lowercase(Locale.ROOT)
            if (key in localRemoved || !seen.add(key)) continue
            out += tag
        }
        for (tag in local) {
            val key = tag.lowercase(Locale.ROOT)
            if (key in localAdded && seen.add(key)) out += tag
        }
        return out
    }

    private fun tagSetEquals(a: List<String>, b: List<String>): Boolean =
        a.map { it.lowercase(Locale.ROOT) }.toSet() == b.map { it.lowercase(Locale.ROOT) }.toSet()
}
