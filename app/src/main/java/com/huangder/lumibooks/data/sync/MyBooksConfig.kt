package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.BuildConfig
import com.huangder.lumibooks.domain.model.Book

/** my-books 服务器接入常量与本地书判定工具。 */
object MyBooksConfig {
    const val SERVER_URL = "https://helloac.top"
    const val LIBRARY_KEY = "mybooks:helloac.top"
    const val BOOK_ID_PREFIX = "mybooks-"
    const val SOURCE_URI_PREFIX = "mybooks://book/"

    /** 编译进 APK 的管理令牌；为空时只读。 */
    val adminToken: String get() = BuildConfig.MYBOOKS_ADMIN_TOKEN.trim()

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
