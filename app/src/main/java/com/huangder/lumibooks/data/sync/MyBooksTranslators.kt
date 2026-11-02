package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import java.util.Locale

/** 译者独立于作者；兼容数组、JSON TEXT 缓存与手工分隔串。 */
fun parseMyBooksTranslators(raw: Any?): List<String> {
    val values: List<String> = when (raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it) as? String }
        is String -> if (raw.trim().startsWith("[")) {
            return runCatching { parseMyBooksTranslators(JSONArray(raw)) }.getOrDefault(emptyList())
        } else raw.split('、', ';', '；', '/', '&')
        else -> emptyList()
    }
    return values.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
}
