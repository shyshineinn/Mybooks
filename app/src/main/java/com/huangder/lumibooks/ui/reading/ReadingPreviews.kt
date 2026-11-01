package com.huangder.lumibooks.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.DailyTotal
import com.huangder.lumibooks.domain.model.Note
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.ui.bookshelf.BookDetailsUiState
import com.huangder.lumibooks.ui.bookshelf.BookReadingTab
import com.huangder.lumibooks.ui.bookshelf.FileInfoCard
import com.huangder.lumibooks.ui.bookshelf.MyBooksMetaUiState
import com.huangder.lumibooks.ui.statistics.AnnotationPeriodSummary
import com.huangder.lumibooks.ui.statistics.DailyReading
import com.huangder.lumibooks.ui.statistics.MostReadBook
import com.huangder.lumibooks.ui.statistics.MonthlyHeatmap
import com.huangder.lumibooks.ui.statistics.PeriodSegmentedControl
import com.huangder.lumibooks.ui.statistics.ReadingSummary
import com.huangder.lumibooks.ui.statistics.StatisticsUiState
import com.huangder.lumibooks.ui.statistics.WeeklyOverview
import com.huangder.lumibooks.ui.statistics.YearlyHeatmap
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.KaiTi
import com.huangder.lumibooks.ui.theme.resolveAppFontFamily
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Preview-only fixtures. Production screens never read this object; every card
 * below is driven by the same models the real ViewModels emit.
 */
internal object PreviewSamples {
    val book = Book(
        id = "preview-book",
        title = "愤怒的葡萄",
        author = "[美]约翰·斯坦贝克",
        filePath = "/storage/emulated/0/Android/data/top.helloac.mybooks/files/books/mybooks-32.epub",
        coverPath = null,
        format = BookFormat.EPUB,
        lastReadTime = epoch(0, 15, 18),
        readingProgress = 0.42f,
        createdAt = epoch(21, 0, 4)
    )

    private fun epoch(daysAgo: Long, hour: Int, minute: Int): Long =
        LocalDate.now().minusDays(daysAgo)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun date(daysAgo: Long): String = LocalDate.now().minusDays(daysAgo).toString()

    fun sessions(): List<ReadingSessionItem> = listOf(
        session(
            id = 1,
            daysAgo = 0,
            startHour = 21,
            startMinute = 15,
            minutes = 32,
            fromChapter = 2,
            toChapter = 3,
            fromProgress = 0.30f,
            toProgress = 0.36f,
            note = "这一章把一家人的迁徙写得很克制，却比任何控诉都重。",
            mood = "rewarding"
        ),
        session(
            id = 2,
            daysAgo = 1,
            startHour = 20,
            startMinute = 5,
            minutes = 18,
            fromChapter = 1,
            toChapter = 2,
            fromProgress = 0.18f,
            toProgress = 0.30f
        ),
        session(
            id = 3,
            daysAgo = 2,
            startHour = 7,
            startMinute = 40,
            minutes = 8,
            fromChapter = 0,
            toChapter = 1,
            fromProgress = 0.10f,
            toProgress = 0.18f
        )
    )

    private fun session(
        id: Long,
        daysAgo: Long,
        startHour: Int,
        startMinute: Int,
        minutes: Int,
        fromChapter: Int,
        toChapter: Int,
        fromProgress: Float,
        toProgress: Float,
        note: String? = null,
        mood: String? = null
    ): ReadingSessionItem {
        val start = epoch(daysAgo, startHour, startMinute)
        val sessionDate = date(daysAgo)
        return ReadingSessionItem(
            session = ReadingSession(
                id = id,
                bookId = book.id,
                date = sessionDate,
                startTime = start,
                endTime = start + minutes * 60_000L,
                duration = minutes * 60_000L,
                startChapterIndex = fromChapter,
                endChapterIndex = toChapter,
                startChapterTitle = "第 ${fromChapter + 1} 章",
                endChapterTitle = "第 ${toChapter + 1} 章",
                startProgress = fromProgress,
                endProgress = toProgress,
                mode = "slide",
                note = note,
                mood = mood,
                dailyContributions = mapOf(sessionDate to minutes * 60_000L),
                contributedDuration = minutes * 60_000L
            ),
            bookTitle = book.title,
            bookAuthor = book.author,
            coverPath = book.coverPath
        )
    }

    fun notes(): List<Note> = listOf(
        Note(
            bookId = book.id,
            chapterIndex = 2,
            startPosition = 12,
            endPosition = 40,
            selectedText = "土地是唯一值得为之奋斗的东西，唯一值得为之工作、为之流血的东西。",
            note = "从土地到公路，家的含义一直在变。",
            color = "#FFF3B0",
            createdAt = epoch(0, 20, 40)
        ),
        Note(
            bookId = book.id,
            chapterIndex = 1,
            startPosition = 6,
            endPosition = 24,
            selectedText = "人们像河水一样流淌，寻找可以扎根的地方。",
            note = "",
            color = "#FFF3B0",
            createdAt = epoch(1, 20, 20)
        ),
        Note(
            bookId = book.id,
            chapterIndex = 0,
            startPosition = 2,
            endPosition = 18,
            selectedText = "愤怒不是终点，它只是让人继续向前的力气。",
            note = "",
            color = "#FFF3B0",
            createdAt = epoch(2, 7, 55)
        )
    )

    fun statistics(period: Int, empty: Boolean = false): StatisticsUiState {
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val weekMinutes = if (empty) intArrayOf(0, 0, 0, 0, 0, 0, 0) else intArrayOf(8, 18, 0, 0, 32, 0, 0)
        val weekly = (0..6).map { index ->
            DailyReading(
                date = weekStart.plusDays(index.toLong()).toString(),
                duration = weekMinutes[index] * 60_000L,
                dayOfWeek = ""
            )
        }
        // Early in a month there is little to draw; show the previous month instead.
        val monthAnchor = if (today.dayOfMonth < 10) today.minusMonths(1) else today
        val monthDays = if (empty) emptyMap() else (1..monthAnchor.lengthOfMonth()).associate { day ->
            val minutes = when {
                day % 7 == 0 -> 65
                day % 5 == 0 -> 32
                day % 3 == 0 -> 15
                day % 2 == 0 -> 8
                else -> 0
            }
            String.format(java.util.Locale.US, "%04d-%02d-%02d", monthAnchor.year, monthAnchor.monthValue, day) to minutes * 60_000L
        }
        val yearly = if (empty) emptyMap() else buildMap {
            for (month in 1..today.monthValue.coerceAtLeast(1)) {
                put(LocalDate.of(today.year, month, 5).toString(), 30 * 60_000L)
                if (month != today.monthValue || today.dayOfMonth > 12) {
                    put(LocalDate.of(today.year, month, 12).toString(), 45 * 60_000L)
                }
            }
        }
        val sessions = if (empty) emptyList() else sessions()
        return StatisticsUiState(
            todayReadingTime = if (empty) 0 else 32 * 60_000L,
            monthlyReadingTime = monthDays.values.sum(),
            dailyGoal = 30,
            weeklyData = weekly,
            mostReadBooks = if (empty) emptyList() else listOf(
                MostReadBook(book.id, book.title, book.author, null, 58 * 60_000L),
                MostReadBook("preview-2", "长日将尽", "[英]石黑一雄", null, 41 * 60_000L)
            ),
            annotationSummary = if (empty) AnnotationPeriodSummary() else AnnotationPeriodSummary(
                highlights = 12,
                underlines = 3,
                notes = 5,
                bookmarks = 2,
                topTags = listOf("土地" to 4, "迁徙" to 3)
            ),
            currentStreak = if (empty) 0 else 4,
            longestStreak = if (empty) 0 else 12,
            monthlyDailyData = monthDays,
            yearlyDailyData = yearly,
            selectedTab = period,
            displayMonth = monthAnchor.monthValue - 1,
            displayYear = monthAnchor.year,
            displayWeekOffset = 0,
            periodSessions = sessions,
            recentSessions = sessions,
            weeklyLoading = false,
            monthlyLoading = false,
            yearlyLoading = false,
            sessionsLoading = false,
            recentSessionsLoading = false
        )
    }

    fun bookState(withSessions: Boolean): BookDetailsUiState {
        val sessions = if (withSessions) sessions() else emptyList()
        return BookDetailsUiState(
            book = if (withSessions) book else book.copy(lastReadTime = 0L),
            totalReadingTime = sessions.sumOf { it.duration },
            activeDays = if (withSessions) 3 else 0,
            bookmarkCount = if (withSessions) 2 else 0,
            noteCount = if (withSessions) 3 else 0,
            sessions = sessions,
            dailyTotals = if (withSessions) emptyList() else emptyList<DailyTotal>(),
            notes = if (withSessions) notes() else emptyList(),
            sessionsLoaded = true,
            isLoading = false
        )
    }

    fun history(): ReadingHistoryUiState = ReadingHistoryUiState(
        sessions = sessions(),
        books = listOf(ReadingHistoryBookOption(book.id, book.title)),
        filter = ReadingHistoryFilter(),
        loading = false
    )
}

@Composable
private fun PreviewPage(content: @Composable ColumnScope.() -> Unit) {
    EBookReaderTheme(
        appTheme = "lumi",
        darkTheme = false,
        lumiBackgroundScene = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.PageBg)
                .verticalScroll(rememberScrollState()),
            content = content
        )
    }
}

@Composable
private fun PreviewStatistics(period: Int, empty: Boolean) {
    val state = PreviewSamples.statistics(period, empty)
    PreviewPage {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "统计",
            fontSize = AppType.Display,
            fontWeight = FontWeight.Bold,
            fontFamily = resolveAppFontFamily(KaiTi),
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(horizontal = AppSpace.lg)
        )
        Spacer(Modifier.height(24.dp))
        PeriodSegmentedControl(
            labels = listOf("周", "月", "年"),
            selectedIndex = period,
            onSelected = {},
            backdrop = null
        )
        Spacer(Modifier.height(20.dp))
        ReadingSummary(state, period)
        Spacer(Modifier.height(24.dp))
        when (period) {
            0 -> WeeklyOverview(state, onPrevious = {}, onNext = {}, onOpenDay = {})
            1 -> MonthlyHeatmap(state, onPrevious = {}, onNext = {}, onOpenDay = {})
            else -> YearlyHeatmap(state, onPrevious = {}, onNext = {}, onOpenDay = {})
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Preview(name = "统计 · 周（有数据）", widthDp = 393, heightDp = 1100, showBackground = true)
@Composable
private fun StatisticsWeeklyDataPreview() = PreviewStatistics(period = 0, empty = false)

@Preview(name = "统计 · 周（零数据）", widthDp = 393, heightDp = 1100, showBackground = true)
@Composable
private fun StatisticsWeeklyEmptyPreview() = PreviewStatistics(period = 0, empty = true)

@Preview(name = "统计 · 月", widthDp = 393, heightDp = 1100, showBackground = true)
@Composable
private fun StatisticsMonthlyPreview() = PreviewStatistics(period = 1, empty = false)

@Preview(name = "统计 · 年", widthDp = 393, heightDp = 1100, showBackground = true)
@Composable
private fun StatisticsYearlyPreview() = PreviewStatistics(period = 2, empty = false)

@Preview(name = "书籍阅读档案 · 有记录", widthDp = 393, heightDp = 1400, showBackground = true)
@Composable
private fun BookReadingWithSessionsPreview() {
    PreviewPage {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            BookReadingTab(
                book = PreviewSamples.book,
                state = PreviewSamples.bookState(withSessions = true),
                serverMeta = null,
                onOpenHistory = {},
                onOpenAnnotations = {},
                onOpenSession = {}
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Preview(name = "书籍阅读档案 · 无记录", widthDp = 393, heightDp = 1100, showBackground = true)
@Composable
private fun BookReadingEmptyPreview() {
    PreviewPage {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            BookReadingTab(
                book = PreviewSamples.book,
                state = PreviewSamples.bookState(withSessions = false),
                serverMeta = null,
                onOpenHistory = {},
                onOpenAnnotations = {},
                onOpenSession = {}
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Preview(name = "全部阅读记录", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun ReadingHistoryPreview() {
    EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
        Box(Modifier.fillMaxSize().background(AppColors.PageBg)) {
            ReadingHistoryTimelineContent(
                state = PreviewSamples.history(),
                selectedPeriodLabel = "本月",
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
}

@Preview(name = "单次阅读详情", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun ReadingSessionDetailPreview() {
    EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
        Box(Modifier.fillMaxSize().background(AppColors.PageBg).padding(24.dp)) {
            ReadingSessionDetailContent(
                item = PreviewSamples.sessions().first(),
                onClose = {},
                onSaveNote = { _, _ -> true }
            )
        }
    }
}

@Preview(name = "阅读结束总结", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun ReadingSessionSummaryPreview() {
    EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
        Box(Modifier.fillMaxSize().background(AppColors.PageBg).padding(24.dp)) {
            ReadingSessionSummaryContent(
                summary = ReadingSessionSummary(
                    durationMillis = 32 * 60_000L,
                    progressDeltaPercent = 6,
                    annotationCount = 3,
                    sessionId = 1
                ),
                onClose = {},
                onDone = { _, _ -> }
            )
        }
    }
}

@Preview(name = "书籍文件信息 · 折叠", widthDp = 393, heightDp = 900, showBackground = true)
@Composable
private fun BookFileCollapsedPreview() {
    PreviewPage {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            FileInfoCard(
                book = PreviewSamples.book,
                metaState = MyBooksMetaUiState(),
                downloadState = null
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
