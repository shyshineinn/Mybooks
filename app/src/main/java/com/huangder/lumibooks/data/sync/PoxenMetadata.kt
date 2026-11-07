package com.huangder.lumibooks.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Converts native `/refer` candidates into the legacy shape used by the existing editors. */
internal object PoxenMetadata {
    fun lookup(raw: JSONObject, isbn: String): String {
        val candidates = candidates(raw)
        if (candidates.isEmpty()) return JSONObject().put("status", "not_found").toString()
        val query = normalizeIsbn(isbn)
        val matched = candidates.firstOrNull { candidate ->
            normalizeIsbn(candidate.optString("isbn")) == query ||
                normalizeIsbn(candidate.optString("isbn13")) == query
        }
        val best = matched ?: candidates.first()
        if (matched != null && query.isNotBlank()) best.put("isbn", query)
        return JSONObject().put("status", "found").put("book", best).toString()
    }

    fun search(raw: JSONObject): String {
        val results = JSONArray()
        candidates(raw).forEach(results::put)
        return JSONObject().put("results", results).toString()
    }

    private fun candidates(raw: JSONObject): List<JSONObject> {
        val rows = raw.optJSONArray("books") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                rows.optJSONObject(index)?.let(::candidate)?.let(::add)
            }
        }
    }

    private fun candidate(obj: JSONObject): JSONObject? {
        val title = obj.optString("title").trim()
        if (title.isEmpty()) return null
        val authors = obj.optJSONArray("authors")
        val author = if (authors != null) {
            (0 until authors.length()).mapNotNull { authors.optString(it).trim().takeIf(String::isNotEmpty) }
                .joinToString("、")
        } else {
            obj.optString("author").trim()
        }
        val isbn = obj.optString("isbn").trim()
        val isbn13 = obj.optString("isbn13").trim()
        val providerKey = obj.optString("provider_key").trim().lowercase()
        val providerValue = obj.optString("provider_value").trim()
        val pubYear = obj.optString("pubyear").trim()
            .ifBlank { obj.optString("pubdate").trim().take(4) }
        return JSONObject()
            .put("title", title)
            .put("author", author)
            .put("translators", obj.optJSONArray("translators") ?: JSONArray())
            .put("summary", Jsoup.parse(obj.optString("comments")).wholeText().trim())
            .put("publisher", obj.optString("publisher").trim())
            .put("pub_year", pubYear)
            .put("isbn", isbn13.ifBlank { isbn })
            .put("isbn13", isbn13)
            .put("rating", obj.optDouble("rating", 0.0))
            .put("douban_id", if (providerKey == "douban" || providerKey == "douban_v2") providerValue else "")
            .put("openlibrary_id", if (providerKey == "openlibrary") providerValue else "")
            .put("cover", obj.optString("cover_url").trim().ifBlank { obj.optString("cover").trim() })
            .put("tags", obj.optJSONArray("tags") ?: JSONArray())
    }

    private fun normalizeIsbn(raw: String): String =
        raw.uppercase().filter { it.isDigit() || it == 'X' }
}
