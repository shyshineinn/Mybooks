package com.huangder.lumibooks.ui.reading

import com.huangder.lumibooks.domain.model.Bookmark
import com.huangder.lumibooks.domain.model.Note
import com.huangder.lumibooks.domain.model.ReadingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingSessionSummaryTest {
    private fun note(bookId: String, id: Long, type: String = "highlight", origin: String = Note.ORIGIN_MANUAL) = Note(
        id = id,
        bookId = bookId,
        chapterIndex = 0,
        startPosition = 0,
        endPosition = 2,
        selectedText = "text",
        note = "",
        color = "#ff0000",
        createdAt = 100,
        type = type,
        origin = origin,
        sourceRuleId = if (origin == Note.ORIGIN_HIGHLIGHT_RULE) "rule" else null,
        sourceMatchKey = if (origin == Note.ORIGIN_HIGHLIGHT_RULE) "match" else null
    )

    private fun bookmark(bookId: String, id: Long) = Bookmark(
        id = id,
        bookId = bookId,
        chapterIndex = 0,
        position = 0f,
        title = "bookmark",
        createdAt = 100
    )

    @Test fun annotationsAreFilteredByBookAndManualMarksOnly() {
        val notes = listOf(
            note("current", 1),
            note("other", 2),
            note("current", 3, type = "pdf_ink_stroke"),
            note("current", 4, origin = Note.ORIGIN_HIGHLIGHT_RULE)
        )
        val bookmarks = listOf(bookmark("current", 5), bookmark("other", 6))
        assertEquals(2, countSessionMarks(notes, bookmarks, "current"))
        assertEquals(2, countSessionMarks(notes, bookmarks, "other"))
    }

    @Test fun progressDeltaUsesRealPercentChange() {
        val session = ReadingSession(
            bookId = "book",
            date = "2026-11-01",
            startTime = 0,
            endTime = 1,
            duration = 60_000,
            startProgress = 0.30f,
            endProgress = 0.36f
        )
        assertEquals(6, sessionProgressDeltaPercent(session))
        assertNull(sessionProgressDeltaPercent(session.copy(startProgress = 0f, endProgress = 0f)))
    }
}
