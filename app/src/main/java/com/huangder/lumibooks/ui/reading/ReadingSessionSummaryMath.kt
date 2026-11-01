package com.huangder.lumibooks.ui.reading

import com.huangder.lumibooks.domain.model.Bookmark
import com.huangder.lumibooks.domain.model.Note
import com.huangder.lumibooks.domain.model.ReadingSession

fun countSessionMarks(
    notes: List<Note>,
    bookmarks: List<Bookmark>,
    bookId: String
): Int {
    val manualNotes = notes.filter {
        it.bookId == bookId && !it.isInkStroke && (!it.isGeneratedByHighlightRule || it.isNoteEntry)
    }
    return manualNotes.size + bookmarks.count { it.bookId == bookId }
}

fun sessionProgressDeltaPercent(session: ReadingSession): Int? {
    if (session.startProgress <= 0f && session.endProgress <= 0f) return null
    return ((session.endProgress - session.startProgress) * 100f).toInt()
}
