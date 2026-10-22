package com.helloac.mybooks.remote

import com.huangder.lumibooks.domain.model.Book

/** my-books 服务器接入常量与本地书判定工具。 */
object MyBooksConfig {
    const val SERVER_URL = "https://helloac.top"
    const val LIBRARY_KEY = "mybooks:helloac.top"
    const val BOOK_ID_PREFIX = "mybooks-"
    const val SOURCE_URI_PREFIX = "mybooks://book/"

    /** 本地 Book.id → 服务器书 id；不是服务器书返回 null。 */
    fun remoteIdOf(bookId: String): Long? =
        bookId.removePrefix(BOOK_ID_PREFIX).toLongOrNull()

    /** 判断一本本地书是否来自 my-books 服务器。 */
    fun isMyBooks(book: Book): Boolean =
        book.remoteLibraryKey == LIBRARY_KEY ||
            book.id.startsWith(BOOK_ID_PREFIX) ||
            book.sourceUri?.startsWith(SOURCE_URI_PREFIX) == true
}
