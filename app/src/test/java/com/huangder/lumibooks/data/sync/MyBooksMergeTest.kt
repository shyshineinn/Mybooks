package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBooksMergeTest {

    private fun book(title: String, author: String = "Author") = Book(
        id = "mybooks-1",
        title = title,
        author = author,
        filePath = "",
        coverPath = null,
        format = BookFormat.EPUB,
        lastReadTime = 0L,
        readingProgress = 0f,
        createdAt = 0L
    )

    private fun baseJson(updatedAt: String = "2026-10-20T00:00:00.000Z") = JSONObject()
        .put("title", "Title")
        .put("author", "Author")
        .put("status", "wish")
        .put("summary", "Summary")
        .put("rating", 0.0)
        .put("series", "")
        .put("series_index", 0)
        .put("publisher", "")
        .put("pub_year", "")
        .put("isbn", "")
        .put("douban_id", "")
        .put("cover", "")
        .put("tags", JSONArray())
        .put("identifiers", JSONObject())
        .put("custom", JSONObject())
        .put("read_logs", JSONArray())
        .put("updated_at", updatedAt)

    private fun merge(
        base: JSONObject = baseJson(),
        remote: JSONObject = JSONObject(base.toString()),
        overrides: JSONObject = JSONObject(),
        editedAt: JSONObject = JSONObject(),
        baseTags: List<String> = emptyList(),
        localTags: List<String> = emptyList()
    ) = MyBooksMerge.merge(
        book = book("Title"),
        baseJson = base.toString(),
        remoteJson = remote.toString(),
        overridesJson = overrides.toString(),
        editedAtJson = editedAt.toString(),
        baseTags = baseTags,
        localTags = localTags,
        coverDirty = false,
        coverEditedAt = 0L
    )

    @Test
    fun localTitleChangeIsPatchedWhenRemoteUnchanged() {
        val result = MyBooksMerge.merge(
            book = book("New Title"),
            baseJson = baseJson().toString(),
            remoteJson = baseJson().toString(),
            overridesJson = "{}",
            editedAtJson = "{}",
            baseTags = emptyList(),
            localTags = emptyList(),
            coverDirty = false,
            coverEditedAt = 0L
        )
        assertEquals("New Title", result.patch.optString("title"))
        assertEquals(1, result.patch.length())
    }

    @Test
    fun remoteClockAheadStillRequiresManualResolution() {
        val base = baseJson()
        val remote = JSONObject(base.toString()).put("title", "Remote Title").put("updated_at", "2026-10-22T00:00:00.000Z")
        val result = MyBooksMerge.merge(book("Local Title"), base.toString(), remote.toString(),
            JSONObject().put("title", "Local Title").toString(),
            JSONObject().put("title", 0L).toString(),
            emptyList(), emptyList(), false, 0L)
        assertTrue(result.conflicts.contains("title"))
        assertFalse(result.patch.has("title"))
        assertFalse(result.dropOverrides.contains("title"))
        assertTrue(result.localWonFields.contains("title"))
    }

    @Test
    fun localClockAheadStillRequiresManualResolution() {
        val base = baseJson()
        val remote = JSONObject(base.toString()).put("title", "Remote Title").put("updated_at", "2026-10-22T00:00:00.000Z")
        val result = MyBooksMerge.merge(book("Local Title"), base.toString(), remote.toString(),
            JSONObject().put("title", "Local Title").toString(),
            JSONObject().put("title", Long.MAX_VALUE).toString(),
            emptyList(), emptyList(), false, 0L)
        assertTrue(result.conflicts.contains("title"))
        assertFalse(result.patch.has("title"))
        assertFalse(result.dropOverrides.contains("title"))
        assertTrue(result.localWonFields.contains("title"))
    }

    @Test
    fun identifiersMergePerType() {
        val base = baseJson().put(
            "identifiers",
            JSONObject().put("goodreads", "1").put("doi", "10.1/x")
        )
        val overrides = JSONObject()
            .put("identifiers.goodreads", "2")
            .put("identifiers.doi", "")
        val result = merge(base = base, overrides = overrides)
        val patch = requireNotNull(result.patch.optJSONObject("identifiers"))
        assertEquals("2", patch.optString("goodreads"))
        assertEquals("", patch.optString("doi"))
        assertFalse(patch.has("openlibrary"))
    }

    @Test
    fun customMergePerLabel() {
        val base = baseJson().put(
            "custom",
            JSONObject().put("mood", "calm").put("score", 5)
        )
        val overrides = JSONObject().put("custom.mood", "happy")
        val result = merge(base = base, overrides = overrides)
        val patch = requireNotNull(result.patch.optJSONObject("custom"))
        assertEquals("happy", patch.optString("mood"))
        assertFalse(patch.has("score"))
    }

    @Test
    fun overrideKeysBuiltByTheHelperAreConsumedByTheMerge() {
        val overrides = JSONObject()
            .put(MyBooksConfig.identifierOverrideKey("goodreads"), "5907")
            .put(MyBooksConfig.customOverrideKey("mood"), "calm")
        val result = merge(overrides = overrides)
        assertEquals("5907", result.patch.optJSONObject("identifiers")?.optString("goodreads"))
        assertEquals("calm", result.patch.optJSONObject("custom")?.optString("mood"))
    }

    @Test
    fun nestedOverrideShapeIsIgnoredSoAdvancedEditsCannotBeSilentlyDropped() {
        val overrides = JSONObject()
            .put("identifiers", JSONObject().put("goodreads", "5907"))
            .put("custom", JSONObject().put("mood", "calm"))
        val result = merge(overrides = overrides)
        assertEquals(0, result.patch.optJSONObject("identifiers")?.length() ?: 0)
        assertEquals(0, result.patch.optJSONObject("custom")?.length() ?: 0)
    }

    @Test
    fun clearedAuthorIsPushedAsEmptyStringInsteadOfPlaceholder() {
        val base = baseJson().put("author", "Author")
        val result = merge(base = base, overrides = JSONObject().put("author", ""))
        assertTrue(result.patch.has("author"))
        assertEquals("", result.patch.optString("author"))
    }

    @Test
    fun blankLocalAuthorWithoutOverrideDoesNotPushAnything() {
        val base = baseJson().put("author", "Author")
        val result = MyBooksMerge.merge(
            book = book("Title", author = ""),
            baseJson = base.toString(),
            remoteJson = base.toString(),
            overridesJson = "{}",
            editedAtJson = "{}",
            baseTags = emptyList(),
            localTags = emptyList(),
            coverDirty = false,
            coverEditedAt = 0L
        )
        assertFalse(result.patch.has("author"))
    }

    @Test
    fun readLogChangeAddsDateFallbackReset() {
        val logs = JSONArray().put(
            JSONObject().put("start", "2026-10-01").put("finish", "2026-10-10")
        )
        val overrides = JSONObject().put("read_logs", logs)
        val result = merge(overrides = overrides)
        assertEquals(1, result.patch.getJSONArray("read_logs").length())
        assertEquals("", result.patch.optString("start_date"))
        assertEquals("", result.patch.optString("finish_date"))
        assertEquals(0, result.patch.optInt("read_count"))
    }

    @Test
    fun blankServerAuthorDoesNotCreatePhantomLocalEdit() {
        val base = baseJson().put("author", "")
        val result = MyBooksMerge.merge(
            book = book("Title", author = "未知作者"),
            baseJson = base.toString(),
            remoteJson = base.toString(),
            overridesJson = "{}",
            editedAtJson = "{}",
            baseTags = emptyList(),
            localTags = emptyList(),
            coverDirty = false,
            coverEditedAt = 0L
        )
        assertFalse(result.patch.has("author"))
        assertTrue(result.patch.length() == 0)
    }

    @Test
    fun tagsMergeKeepsRemoteAddsAndLocalAdds() {
        val result = MyBooksMerge.merge(
            book = book("Title"),
            baseJson = baseJson().put("tags", JSONArray(listOf("A"))).toString(),
            remoteJson = baseJson().put("tags", JSONArray(listOf("A", "C"))).toString(),
            overridesJson = "{}",
            editedAtJson = "{}",
            baseTags = listOf("A"),
            localTags = listOf("A", "B"),
            coverDirty = false,
            coverEditedAt = 0L
        )
        assertEquals(listOf("A", "C", "B"), result.mergedTags)
        assertTrue(result.tagsDirty)
        assertEquals(MyBooksCoverAction.NONE, result.coverAction)
    }

    @Test
    fun doubanFilledTitleAndAuthorAreNotOverwrittenByFileDerivedValues() {
        // 豆瓣填充后服务器上是"三体/刘慈欣"；本地正文副本曾按文件名写入"xxx.epub/未知作者"，
        // 于是这层合并把文件来源的临时值当成"本地编辑"推回服务器，覆盖掉豆瓣数据。
        // 本地行带上服务器那份书名/作者后，两边一致，不产生任何 patch。
        val base = baseJson()
            .put("title", "三体")
            .put("author", "刘慈欣")
        val result = MyBooksMerge.merge(
            book = book("三体", author = "刘慈欣"),
            baseJson = base.toString(),
            remoteJson = base.toString(),
            overridesJson = "{}",
            editedAtJson = "{}",
            baseTags = emptyList(),
            localTags = emptyList(),
            coverDirty = false,
            coverEditedAt = 0L
        )
        assertFalse(result.patch.has("title"))
        assertFalse(result.patch.has("author"))
        assertEquals(0, result.patch.length())
    }

    @Test
    fun differentLocalTitleOrAuthorCountsAsLocalEdit() {
        assertTrue(isLocalTitleAuthorEdit("xxx.epub", "三体"))
        assertTrue(isLocalTitleAuthorEdit("未知作者", "刘慈欣"))
    }

    @Test
    fun blankLocalTitleOrAuthorIsNotTreatedAsLocalEdit() {
        // 导入正文副本过程中的空值不是用户意图，不能推回服务器。
        assertFalse(isLocalTitleAuthorEdit("", "三体"))
        assertFalse(isLocalTitleAuthorEdit("三体", "三体"))
    }
}
