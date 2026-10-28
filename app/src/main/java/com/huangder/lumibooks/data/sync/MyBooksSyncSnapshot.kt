package com.huangder.lumibooks.data.sync

import org.json.JSONObject
import java.time.Instant

data class MyBooksDeletion(val id: Long, val uuid: String, val revision: Long, val deletedAt: String)
data class MyBooksSyncSnapshot(
    val books: List<RemoteBook>,
    val deleted: List<MyBooksDeletion> = emptyList(),
    val libraryId: String? = null,
    val syncVersion: Int = 1
)

/** A malformed or filtered response must never become deletion authority. */
internal fun parseMyBooksSyncSnapshot(json: String, baseUrl: String): MyBooksSyncSnapshot {
    val root = JSONObject(json)
    require((root.opt("sync_version") as? Number)?.toDouble() == 2.0 && root.opt("complete") == true)
    val libraryId = root.get("library_id") as? String
    require(!libraryId.isNullOrBlank())
    val active = root.getJSONArray("books")
    val deleted = root.getJSONArray("deleted")
    val seen = mutableSetOf<Long>()
    val seenUuids = mutableSetOf<String>()
    fun identity(row: JSONObject): Pair<Long, String> {
        val id = positiveSyncInteger(row.get("id"))
        val uuid = row.get("uuid") as? String
        require(!uuid.isNullOrBlank() && seen.add(id) && seenUuids.add(uuid))
        positiveSyncInteger(row.get("revision"))
        return id to uuid
    }
    val books = (0 until active.length()).map {
        val row = active.getJSONObject(it)
        identity(row)
        require(row.optString("title").isNotBlank() && (!row.has("deleted_at") || row.isNull("deleted_at")))
        parseRemoteBook(row.toString(), baseUrl)
    }
    val events = (0 until deleted.length()).map {
        val row = deleted.getJSONObject(it)
        val (id, uuid) = identity(row)
        val date = row.getString("deleted_at")
        Instant.parse(date)
        MyBooksDeletion(id, uuid, positiveSyncInteger(row.get("revision")), date)
    }
    return MyBooksSyncSnapshot(books, events, libraryId, 2)
}

private fun positiveSyncInteger(value: Any): Long {
    require(value is Number)
    val number = value.toLong()
    require(number in 1..9007199254740991L && number.toDouble() == value.toDouble())
    return number
}

internal fun matchesMyBooksDeletion(baseJson: String, event: MyBooksDeletion): Boolean = runCatching {
    val base = JSONObject(baseJson)
    base.optString("uuid").isNotBlank() && base.optString("uuid") == event.uuid &&
        base.optLong("revision", 0L) <= event.revision
}.getOrDefault(false)
