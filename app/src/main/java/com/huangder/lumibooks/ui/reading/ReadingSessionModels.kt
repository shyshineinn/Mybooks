package com.huangder.lumibooks.ui.reading

import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.ReadingSession

/** A session joined with the book metadata its row needs. */
data class ReadingSessionItem(
    val session: ReadingSession,
    val bookTitle: String,
    val bookAuthor: String,
    val coverPath: String?
) {
    val id: Long get() = session.id
    val sessionKey: String get() = session.sessionKey
    val bookId: String get() = session.bookId
    val date: String get() = session.date
    val startTime: Long get() = session.startTime
    val endTime: Long get() = session.endTime
    val duration: Long get() = session.duration
    val dailyContributions: Map<String, Long> get() = session.dailyContributions
    val contributedDuration: Long get() = session.contributedDuration
    val isEffective: Boolean get() = session.isEffective
    val startProgress: Float get() = session.startProgress
    val endProgress: Float get() = session.endProgress
    val startChapterIndex: Int get() = session.startChapterIndex
    val endChapterIndex: Int get() = session.endChapterIndex
    val startChapterTitle: String? get() = session.startChapterTitle
    val endChapterTitle: String? get() = session.endChapterTitle
    val mode: String get() = session.mode
    val note: String? get() = session.note
    val mood: String? get() = session.mood
}

fun List<ReadingSession>.joinBooks(books: List<Book>): List<ReadingSessionItem> {
    val byId = books.associateBy { it.id }
    return mapNotNull { session ->
        val book = byId[session.bookId] ?: return@mapNotNull null
        ReadingSessionItem(
            session = session,
            bookTitle = book.title,
            bookAuthor = book.author,
            coverPath = book.coverPath
        )
    }
}

fun ReadingSessionItem.progressDeltaPercent(): Int? {
    if (startProgress <= 0f && endProgress <= 0f) return null
    return ((endProgress - startProgress) * 100f).toInt()
}
