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
 * 服务端用 null 表示缺省，例如缺少书目修改时间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksJsonTextTest {

    @Test
    fun jsonNullMustNotBeReadAsTheLiteralNullString() {
        val obj = JSONObject("""{"updated_at": null, "title": null}""")
        assertEquals("null", obj.optString("updated_at"))
        assertNull(obj.optTextOrNull("updated_at"))
        assertEquals("", obj.optTextOrEmpty("title"))
    }

    @Test
    fun blankAndAbsentFieldsStayAbsent() {
        val obj = JSONObject("""{"cover":"","title":"深度工作"}""")
        assertNull(obj.optTextOrNull("cover"))
        assertNull(obj.optTextOrNull("missing"))
        assertEquals("深度工作", obj.optTextOrEmpty("title"))
    }
}
