package com.huangder.lumibooks.data.sync

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** The protocol has no row IDs. A unique row plus the complete multiset is an optimistic edit token. */
internal data class ReadLogDateSource(val record: MyBooksReadLog, val snapshot: List<MyBooksReadLog>)
internal enum class ReadLogDateField(val key: String) { START("start"), FINISH("finish") }
internal enum class ReadLogDateFailure { CHANGED, INVALID, ORDER }
internal class ReadLogDateException(val reason: ReadLogDateFailure) : IllegalStateException(reason.name)

internal fun readLogDateSource(log: MyBooksReadLog, snapshot: List<MyBooksReadLog>): ReadLogDateSource? =
    if (snapshot.count { it == log } == 1) ReadLogDateSource(log, snapshot.toList()) else null

/** Preserve array order, duplicate rounds, the other date, and all unrelated fields. */
internal fun editReadLogDate(raw: Any?, source: ReadLogDateSource, field: ReadLogDateField, date: String): JSONArray {
    fun fail(reason: ReadLogDateFailure): Nothing = throw ReadLogDateException(reason)
    fun valid(value: String) = value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
        runCatching { LocalDate.parse(value).year in 1..9999 }.getOrDefault(false)
    if (!valid(date)) fail(ReadLogDateFailure.INVALID)
    val array = when (raw) {
        is JSONArray -> JSONArray(raw.toString())
        is String -> runCatching { JSONArray(raw) }.getOrNull()
        else -> null
    } ?: fail(ReadLogDateFailure.CHANGED)
    val rows = (0 until array.length()).map { index ->
        val row = array.optJSONObject(index) ?: fail(ReadLogDateFailure.CHANGED)
        MyBooksReadLog(row.optTextOrEmpty("start").trim(), row.optTextOrEmpty("finish").trim())
    }
    val nonempty = rows.filter { it.start.isNotBlank() || it.finish.isNotBlank() }
    if (nonempty.groupingBy { it }.eachCount() != source.snapshot.groupingBy { it }.eachCount() ||
        rows.count { it == source.record } != 1) fail(ReadLogDateFailure.CHANGED)
    val index = rows.indexOf(source.record)
    if ((if (field == ReadLogDateField.START) rows[index].start else rows[index].finish).isBlank()) {
        fail(ReadLogDateFailure.CHANGED)
    }
    array.getJSONObject(index).put(field.key, date)
    for (i in 0 until array.length()) {
        val row = array.getJSONObject(i)
        val start = row.optTextOrEmpty("start").trim()
        val finish = row.optTextOrEmpty("finish").trim()
        if ((start.isNotEmpty() && !valid(start)) || (finish.isNotEmpty() && !valid(finish))) fail(ReadLogDateFailure.INVALID)
        if (start.isNotEmpty() && finish.isNotEmpty() && finish < start) fail(ReadLogDateFailure.ORDER)
    }
    return array
}
