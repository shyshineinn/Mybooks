package com.huangder.lumibooks.domain.model

import java.util.Locale

/** 服务器阅读状态；仅用于 MyBooks 书的“未读/在读/已读”分类。WISH 保留旧协议值 wish。 */
enum class ReadingStatus {
    WISH,
    WANT_TO_READ,
    READING,
    DONE;

    companion object {
        fun fromServerStatus(raw: String?): ReadingStatus? =
            when (raw?.trim()?.lowercase(Locale.ROOT)) {
                "wish", "want" -> WISH
                "want_to_read" -> WANT_TO_READ
                "reading" -> READING
                "done", "finished", "read" -> DONE
                else -> null
            }
    }
}
