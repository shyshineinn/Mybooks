package com.huangder.lumibooks.ui.reading

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.ui.components.LiquidGlassDialogHost
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
class ReadingHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    private fun item(id: Long, duration: Long, date: String = "2026-11-01") = ReadingSessionItem(
        session = ReadingSession(
            id = id,
            sessionKey = "session-$id",
            bookId = "book",
            date = date,
            startTime = 1_000L + id * 60_000L,
            endTime = 1_000L + id * 60_000L + duration,
            duration = duration,
            startChapterIndex = 0,
            endChapterIndex = 1,
            startProgress = 0.1f,
            endProgress = 0.2f,
            mode = "slide"
        ),
        bookTitle = "愤怒的葡萄",
        bookAuthor = "作者",
        coverPath = null
    )

    @Test fun timelineShowsSecondsAndAllTimeChipByDefault() {
        val state = ReadingHistoryUiState(
            sessions = listOf(item(1, 5_000), item(2, 20_000), item(3, 60_000)),
            filter = ReadingHistoryFilter(),
            loading = false
        )
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                ReadingHistoryTimelineContent(
                    state = state,
                    selectedPeriodLabel = "全部时间",
                    selectedBookLabel = "全部书籍",
                    selectedModeLabel = "全部方式",
                    onBack = {},
                    onFilter = {},
                    onCalendar = {},
                    onOpenDay = {},
                    onOpenSession = {},
                    onOpenBook = {},
                    onLongPressSession = {}
                )
            }
        }
        compose.onNodeWithText("全部时间").assertIsDisplayed()
        compose.onNodeWithText("5 秒", substring = true).assertIsDisplayed()
        compose.onNodeWithText("20 秒", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("1 分钟", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun filterSheetKeepsDraftUntilApply() {
        var draft by mutableStateOf(ReadingHistoryFilter())
        var applied: ReadingHistoryFilter? = null
        var dismissed = false
        val books = listOf(
            ReadingHistoryBookOption("book-1", "愤怒的葡萄"),
            ReadingHistoryBookOption("book-2", "长日将尽")
        )
        compose.setContent {
            EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                LiquidGlassDialogHost(Modifier.fillMaxSize()) {
                    ReadingHistoryFilterSheet(
                        books = books,
                        draft = draft,
                        onDraftChange = { draft = it },
                        onDismiss = { dismissed = true },
                        onApply = { applied = draft },
                        onReset = { draft = ReadingHistoryFilter() }
                    )
                }
            }
        }
        compose.onNodeWithText("筛选阅读记录").assertIsDisplayed()
        compose.onNodeWithText("搜索书籍").assertIsDisplayed()
        compose.onNodeWithText("全部时间").assertIsDisplayed()
        compose.onNodeWithText("本周").performClick()
        assertEquals(ReadingHistoryPeriod.WEEK, draft.period)
        assertEquals(null, applied)
        compose.onNodeWithText("应用").performClick()
        assertEquals(ReadingHistoryPeriod.WEEK, applied?.period)
        assertTrue(!dismissed)
    }
}
