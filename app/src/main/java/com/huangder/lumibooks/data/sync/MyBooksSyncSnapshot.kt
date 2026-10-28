package com.huangder.lumibooks.data.sync

import org.json.JSONObject
import java.time.Instant

data class MyBooksDeletion(val id: Long, val uuid: String, val revision: Long, val deletedAt: String)
data class MyBooksSyncSnapshot(
    val books: List<RemoteBook>,
    val deleted: List<MyBooksDeletion> = emptyList(),
    val libraryId: String? = null,
    val syncVersion: Int = 1,
    val cursor: String? = null,
    val isFull: Boolean = true,
    val supportsReadingSync: Boolean = false,
    val reading: List<MyBooksReadingHead> = emptyList(),
    val supportsPortableSync: Boolean = false,
    val portableRevision: Long = 0
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


data class MyBooksReadingHead(val id: Long, val uuid: String, val revision: Long)

internal fun parseMyBooksChanges(json: String, baseUrl: String): MyBooksSyncSnapshot {
    val root = JSONObject(json)
    if (root.optInt("sync_version") == 2) return parseMyBooksSyncSnapshot(json, baseUrl)
    require((root.opt("sync_version") as? Number)?.toDouble() == 3.0)
    val complete = root.get("complete") as? Boolean ?: error("Invalid complete flag")
    val cursor = root.get("cursor") as? String
    require(!cursor.isNullOrBlank() && cursor.length <= 4096)
    val legacy = JSONObject(json).put("sync_version", 2).put("complete", true)
    val parsed = parseMyBooksSyncSnapshot(legacy.toString(), baseUrl)
    val heads = root.getJSONArray("reading")
    val ids = mutableSetOf<Long>()
    val deletedIds = parsed.deleted.mapTo(mutableSetOf()) { it.id }
    val active = parsed.books.associateBy { it.id }
    val reading = (0 until heads.length()).map { i ->
        val row = heads.getJSONObject(i)
        val id = positiveSyncInteger(row.get("id"))
        val uuid = row.get("uuid") as? String
        val revision = row.get("revision") as? Number ?: error("Invalid reading revision")
        require(revision.toLong() >= 0 && revision.toDouble() == revision.toLong().toDouble())
        require(!uuid.isNullOrBlank() && ids.add(id) && id !in deletedIds)
        require(active[id] == null || active[id]?.uuid == uuid)
        MyBooksReadingHead(id, uuid, revision.toLong())
    }
    return parsed.copy(syncVersion = 3, cursor = cursor, isFull = complete,
        supportsReadingSync = root.optBoolean("reading_sync"), reading = reading,
        supportsPortableSync = root.optBoolean("portable_sync"), portableRevision = root.optLong("portable_revision"))
}
