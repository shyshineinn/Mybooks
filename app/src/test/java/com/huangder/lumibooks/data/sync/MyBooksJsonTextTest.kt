package com.huangder.lumibooks.data.sync

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归：Android 的 org.json 会保留 JSON null 键（值为 JSONObject.NULL），
 * 此时 optString 返回的是字面量字符串 "null"；JVM 参考实现则会直接丢掉这个键。
 * 所以这里必须用 Robolectric 跑，才能复现设备上的真实行为。
 *
 * 服务端用 null 表示缺省（例如没有心得时的 notes_updated_at），
 * 一旦被当成真实值发回服务端，就会被判成版本冲突（409），导致首次上传心得永远失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksJsonTextTest {

    @Test
    fun jsonNullMustNotBeReadAsTheLiteralNullString() {
        val obj = JSONObject("""{"notes_updated_at": null, "title": null}""")
        assertEquals("null", obj.optString("notes_updated_at"))
        assertNull(obj.optTextOrNull("notes_updated_at"))
        assertEquals("", obj.optTextOrEmpty("title"))
    }

    @Test
    fun serverBookMetaTreatsMissingNotesAsAbsent() {
        val meta = ServerBookMeta.fromJson(
            """{"id":1,"title":"三体","author":"刘慈欣","has_notes":false,"notes_size":0,"notes_updated_at":null}""",
            1L
        )
        assertNull(meta.notesUpdatedAt)
    }

    @Test
    fun remoteBookTreatsMissingNotesAsAbsent() {
        val book = parseRemoteBook(
            """{"id":1,"title":"三体","author":"刘慈欣","has_notes":false,"notes_updated_at":null}""",
            "https://helloac.top"
        )
        assertNull(book.notesUpdatedAt)
    }

    @Test
    fun notesPayloadTreatsMissingUpdatedAtAsAbsent() {
        val payload = MyBooksNotesPayload.fromJson(
            """{"has_notes":false,"content":"","size":0,"updated_at":null}"""
        )
        assertNull(payload.updatedAt)
    }

    @Test
    fun blankAndAbsentFieldsStayAbsent() {
        val obj = JSONObject("""{"cover":"","title":"深度工作"}""")
        assertNull(obj.optTextOrNull("cover"))
        assertNull(obj.optTextOrNull("missing"))
        assertEquals("深度工作", obj.optTextOrEmpty("title"))
    }
}
