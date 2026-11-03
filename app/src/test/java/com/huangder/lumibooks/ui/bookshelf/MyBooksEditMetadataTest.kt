package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.data.sync.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MyBooksEditMetadataTest {
    @Test fun singleMultipleAndAbsentTranslatorsStaySeparateFromAuthors() {
        listOf("[]", "[\"甲\"]", "[\"甲\",\"乙\"]").forEach { raw ->
            val obj = JSONObject().put("title", "书").put("author", "作者").put("translators", JSONArray(raw))
            val candidate = requireNotNull(parseMyBooksCandidate(obj))
            assertEquals("作者", candidate.author)
            assertEquals(parseMyBooksTranslators(raw), candidate.translators)
            val draft = applyMyBooksCandidate(MyBooksEditUiState(), candidate, "完成")
            val payload = buildMyBooksCreatePayload(draft)
            assertEquals(raw, payload.getJSONArray("translators").toString())
            assertEquals("作者", payload.getString("author"))
            assertEquals(candidate.translators, ServerBookMeta.fromJson(payload.toString(), 1).translators)
        }
        assertEquals(listOf("Smith, John", "Jones, Mary"), parseMyBooksTranslators("Smith, John、Jones, Mary"))
        assertEquals(emptyList<String>(), parseMyBooksCandidate(JSONObject().put("title", "旧服务器"))!!.translators)
        assertEquals(listOf("A B", "甲·乙"), parseMyBooksTranslators(" A  B 、a b / 甲·乙"))
    }

    @Test fun rawDoubanKeysUseTheReadableLocalizedLabel() {
        val meta = ServerBookMeta.fromJson(
            """{"title":"书","identifiers":{"douban":"123"},"custom_display":{"douban_id":"456"}}""",
            1
        )
        val labelFor: (String) -> String = {
            if (it.equals("douban", true) || it.equals("douban_id", true)) "豆瓣" else it
        }
        assertEquals("豆瓣", identifierRows(meta, labelFor).single { it.value == "123" }.label)
        assertEquals("豆瓣", customRows(meta, labelFor = labelFor).single { it.value == "456" }.label)
    }

    @Test fun missingTranslatorsAndHiddenMetadataNeverEraseFilledValues() {
        val legacyCustom = ServerBookMeta.fromJson("""{"title":"书","custom_display":{"译者":"旧自定义值"}}""", 1)
        assertEquals("旧自定义值", customRows(legacyCustom).single { it.label == "译者" }.value)
        val original = MyBooksEditUiState(title="书", author="作者", translators="甲、乙", publisher="P", pubYear="2000", rating=8.8f, doubanId="123")
        val missing = requireNotNull(parseMyBooksCandidate(JSONObject().put("title", "改名")))
        val applied = applyMyBooksCandidate(original, missing, "完成")
        assertEquals(original.translators, applied.translators)
        assertEquals(original.publisher, applied.publisher)
        assertEquals(original.pubYear, applied.pubYear)
        assertEquals(original.rating, applied.rating)
        assertEquals(original.doubanId, applied.doubanId)
        val base = buildMyBooksCreatePayload(original).put("rating", 8.8)
        val changed = linkedMapOf<String, Any?>(); val managed = linkedSetOf<String>()
        collectMyBooksScalarChanges(base, original.copy(title="改名", status="reading"), changed, managed)
        assertEquals(setOf("title"), changed.keys)
        assertFalse("阅读状态已迁出编辑弹窗，不能由保存流程清理 override", "status" in managed)
        assertTrue("translators" in managed)
        val corrected = linkedMapOf<String, Any?>()
        collectMyBooksScalarChanges(base, original.copy(translators="丙、丁"), corrected, linkedSetOf())
        assertEquals("[\"丙\",\"丁\"]", corrected["translators"].toString())
        val cleared = linkedMapOf<String, Any?>()
        collectMyBooksScalarChanges(base, original.copy(translators=""), cleared, linkedSetOf())
        assertEquals("[]", cleared["translators"].toString())
    }
}
