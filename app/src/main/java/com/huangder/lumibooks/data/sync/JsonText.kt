package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 的 org.json 在读到 JSON null 时，optString 会返回字面量字符串 "null" 而不是空。
 * 服务端大量字段用 null 表示缺省（例如没有心得时的 notes_updated_at），直接拿 optString 的结果
 * 会得到 "null"，再把它当 expected_updated_at 发回服务端就会被判成版本冲突。
 */
internal fun JSONObject.optTextOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val text = opt(key)?.toString()?.trim().orEmpty()
    return text.takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
}

internal fun JSONObject.optTextOrEmpty(key: String): String = optTextOrNull(key).orEmpty()

/** 数组元素同理：JSON null 元素会读成 "null"。 */
internal fun JSONArray.optTextAt(index: Int): String? {
    if (index < 0 || index >= length()) return null
    if (isNull(index)) return null
    val text = opt(index)?.toString()?.trim().orEmpty()
    return text.takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
}
