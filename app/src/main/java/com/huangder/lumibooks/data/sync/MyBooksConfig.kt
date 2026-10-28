package com.huangder.lumibooks.data.sync

import android.content.Context
import com.huangder.lumibooks.data.local.MyBooksConnectionStore
import com.huangder.lumibooks.domain.model.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MyBooksConnection(
    val serverUrl: String = "",
    val username: String = "",
    val loggedIn: Boolean = false
)

/** my-books 服务器接入常量与本地书判定工具。 */
object MyBooksConfig {
    private val state = MutableStateFlow(MyBooksConnection())
    val connection: StateFlow<MyBooksConnection> = state.asStateFlow()
    @Volatile private var deviceToken: String = ""
    val SERVER_URL: String get() = state.value.serverUrl
    val LIBRARY_KEY: String get() = "mybooks:" + SERVER_URL.substringAfter("://")
    const val BOOK_ID_PREFIX = "mybooks-"
    const val SOURCE_URI_PREFIX = "mybooks://book/"

    /** 保留调用接口名称；值来自加密保存的独立设备会话，APK 不内置管理令牌。 */
    val adminToken: String get() = deviceToken

    fun initialize(context: Context) {
        val store = MyBooksConnectionStore(context)
        deviceToken = store.token()
        state.value = MyBooksConnection(store.serverUrl ?: "", store.username, deviceToken.isNotBlank())
    }
    fun save(context: Context, serverUrl: String, username: String, token: String) {
        val normalized = MyBooksEndpoint.normalize(serverUrl)
        MyBooksConnectionStore(context).save(normalized, username, token)
        deviceToken = token
        state.value = MyBooksConnection(normalized, username, true)
    }
    fun logout(context: Context) {
        MyBooksConnectionStore(context).clearToken()
        deviceToken = ""
        state.value = state.value.copy(loggedIn = false)
    }

    fun hasWriteToken(): Boolean = adminToken.isNotEmpty()

    /** 本地 Book.id → 服务器书 id；不是服务器书返回 null。 */
    fun remoteIdOf(bookId: String): Long? =
        bookId.removePrefix(BOOK_ID_PREFIX).toLongOrNull()

    /** 判断一本本地书是否来自 my-books 服务器。 */
    fun isMyBooks(book: Book): Boolean =
        book.remoteLibraryKey == LIBRARY_KEY ||
            book.id.startsWith(BOOK_ID_PREFIX) ||
            book.sourceUri?.startsWith(SOURCE_URI_PREFIX) == true

    /**
     * 高级字段在 overrides 里的 key 形态。写入端和读取端必须用同一套，
     * 否则改动既不会推送也不会回显。
     */
    const val IDENTIFIER_OVERRIDE_PREFIX = "identifiers."
    const val CUSTOM_OVERRIDE_PREFIX = "custom."

    fun identifierOverrideKey(type: String): String = IDENTIFIER_OVERRIDE_PREFIX + type

    fun customOverrideKey(label: String): String = CUSTOM_OVERRIDE_PREFIX + label
}
