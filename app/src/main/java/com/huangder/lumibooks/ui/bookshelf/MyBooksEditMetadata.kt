package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.data.sync.*
import java.util.Locale
import com.huangder.lumibooks.domain.model.TagNameValidator
import org.json.JSONArray
import org.json.JSONObject

internal fun buildMyBooksCreatePayload(state: MyBooksEditUiState): JSONObject {
    val payload = JSONObject()
        .put("title", state.title.trim())
        .put("author", state.author.trim())
        .put("translators", JSONArray(parseMyBooksTranslators(state.translators)))
        .put("status", state.status)
        .put("summary", state.summary.trim())
        .put("rating", kotlin.math.round(state.rating.toDouble() * 10) / 10)
        .put("series", state.series.trim())
        .put("series_index", state.seriesIndex.trim())
        .put("publisher", state.publisher.trim())
        .put("pub_year", state.pubYear.trim())
        .put("isbn", state.isbn.trim())
        .put("douban_id", state.doubanId.trim())
    val identifiers = JSONObject()
    state.identifiers.forEach { row ->
        val type = row.type.trim().lowercase(Locale.ROOT)
        if (type.isNotEmpty() && row.value.isNotBlank()) identifiers.put(type, row.value.trim())
    }
    payload.put("identifiers", identifiers)
    payload.put("tags", JSONArray(state.tags))
    val custom = JSONObject()
    state.customColumns.forEach { column ->
        val value = state.customValues[column.label].orEmpty()
        if (value.isNotBlank()) custom.put(column.label, customToServerValue(column, value))
    }
    payload.put("custom", custom)
    val logs = JSONArray()
    state.readLogs.forEach { row ->
        val start = row.start.trim()
        val finish = row.finish.trim()
        if (start.isNotEmpty() || finish.isNotEmpty()) {
            logs.put(JSONObject().put("start", start).put("finish", finish))
        }
    }
    payload.put("read_logs", logs)
    return payload
}

internal fun customToServerValue(column: MyBooksCustomColumn, draft: String): Any {
    val text = draft.trim()
    return when {
        column.multiple -> JSONArray(
            text.split('、', ',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
        )
        column.datatype == "int" -> if (text.isEmpty()) "" else text.toLongOrNull() ?: 0L
        column.datatype == "float" || column.datatype == "rating" ->
            if (text.isEmpty()) "" else text.toDoubleOrNull() ?: 0.0
        column.datatype == "bool" -> if (text.isEmpty()) "" else text == "1"
        else -> text
    }
}

private fun parseDoubanTags(raw: Any?): List<String> {
    val values = when (raw) {
        is JSONArray -> buildList { for (i in 0 until raw.length()) raw.optTextAt(i)?.let(::add) }
        is String -> raw.split('、', ',', '，', ';', '；')
        else -> emptyList()
    }
    return values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

internal fun parseMyBooksCandidate(obj: JSONObject?): MyBooksDoubanCandidate? {
    if (obj == null) return null
    val title = obj.optTextOrEmpty("title").trim()
    if (title.isEmpty()) return null
    return MyBooksDoubanCandidate(
        title = title,
        author = obj.optTextOrEmpty("author").trim(),
        translators = parseMyBooksTranslators(obj.opt("translators")),
        summary = obj.optTextOrEmpty("summary").trim(),
        publisher = obj.optTextOrEmpty("publisher").trim(),
        pubYear = obj.optTextOrEmpty("pubYear").ifBlank { obj.optTextOrEmpty("pub_year") }.trim(),
        isbn = obj.optTextOrEmpty("isbn").trim(),
        rating = obj.optDouble("rating", 0.0),
        doubanId = obj.optTextOrEmpty("doubanId").ifBlank { obj.optTextOrEmpty("douban_id") }.trim(),
        coverUrl = obj.optTextOrEmpty("cover").takeIf { it.isNotBlank() },
        openlibraryId = obj.optTextOrEmpty("openlibraryId").ifBlank { obj.optTextOrEmpty("openlibrary_id") }
            .takeIf { it.isNotBlank() },
        tags = parseDoubanTags(obj.opt("tags"))
    )
}

internal fun collectMyBooksScalarChanges(
    base: JSONObject,
    state: MyBooksEditUiState,
    changed: MutableMap<String, Any?>,
    managed: MutableSet<String>
) {
    val fields = listOf("title", "author", "translators", "status", "summary", "rating", "series", "series_index", "publisher", "pub_year", "isbn", "douban_id")
    managed += fields
    val translators = parseMyBooksTranslators(state.translators)
    if (translators != parseMyBooksTranslators(base.opt("translators"))) {
        changed["translators"] = JSONArray(translators)
    }
    val baseValues = mapOf(
        "title" to base.optTextOrEmpty("title").ifBlank { "未命名" },
        "author" to base.optTextOrEmpty("author").ifBlank { "未知作者" },
        "status" to base.optTextOrEmpty("status").ifBlank { "wish" },
        "summary" to base.optTextOrEmpty("summary"),
        "rating" to base.optDouble("rating", 0.0),
        "series" to base.optTextOrEmpty("series"),
        "series_index" to base.opt("series_index")?.toString().takeUnless { it == "null" }.orEmpty(),
        "publisher" to base.optTextOrEmpty("publisher"),
        "pub_year" to base.optTextOrEmpty("pub_year"),
        "isbn" to base.optTextOrEmpty("isbn"),
        "douban_id" to base.optTextOrEmpty("douban_id")
    )
    val draftValues = mapOf(
        "title" to state.title.trim().ifBlank { "未命名" },
        "author" to state.author.trim().ifBlank { "未知作者" },
        "status" to state.status,
        "summary" to state.summary.trim(),
        "rating" to kotlin.math.round(state.rating.toDouble() * 10) / 10,
        "series" to state.series.trim(),
        "series_index" to state.seriesIndex.trim(),
        "publisher" to state.publisher.trim(),
        "pub_year" to state.pubYear.trim(),
        "isbn" to state.isbn.trim(),
        "douban_id" to state.doubanId.trim()
    )
    // 比较沿用占位口径（空值与基线等价，不产生幽灵改动）；
    // 真正提交时用用户原始输入，避免把占位文案写进服务器。
    val rawValues = mapOf(
        "title" to state.title.trim(),
        "author" to state.author.trim()
    )
    for ((key, value) in draftValues) {
        val baseValue = baseValues[key]
        val equal = when (key) {
            "rating" -> (baseValue as? Number)?.toDouble() == (value as Double)
            "series_index" -> (baseValue as? String).orEmpty().ifBlank { "0" } == (value as String).ifBlank { "0" }
            else -> baseValue == value
        }
        if (!equal) changed[key] = rawValues[key] ?: value
    }
}

internal fun applyMyBooksCandidate(state: MyBooksEditUiState, candidate: MyBooksDoubanCandidate, message: String): MyBooksEditUiState {
    var identifiers = state.identifiers
    if (!candidate.openlibraryId.isNullOrBlank()) {
        val index = identifiers.indexOfFirst { it.type.equals("openlibrary", true) }
        identifiers = if (index >= 0) {
            identifiers.toMutableList().also {
                it[index] = it[index].copy(value = candidate.openlibraryId)
            }
        } else {
            identifiers + MyBooksIdentifierDraft("openlibrary", candidate.openlibraryId)
        }
    }
    return state.copy(
        title = candidate.title.ifBlank { state.title },
        author = candidate.author.ifBlank { state.author },
        translators = candidate.translators.takeIf { it.isNotEmpty() }?.joinToString("、") ?: state.translators,
        summary = candidate.summary.ifBlank { state.summary },
        publisher = candidate.publisher.ifBlank { state.publisher },
        pubYear = candidate.pubYear.ifBlank { state.pubYear },
        isbn = candidate.isbn.ifBlank { state.isbn },
        doubanId = candidate.doubanId.ifBlank { state.doubanId },
        rating = if (candidate.rating > 0) candidate.rating.toFloat() else state.rating,
        identifiers = identifiers,
        candidates = emptyList(),
        coverUrl = candidate.coverUrl?.takeIf { it.isNotBlank() } ?: state.coverUrl,
        tags = (state.tags + candidate.tags)
            .map { TagNameValidator.clean(it) }
            .filter { TagNameValidator.isValid(it) }
            .distinctBy { TagNameValidator.normalized(it) },
        lookupMessage = message
    )
}
