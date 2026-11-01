package com.huangder.lumibooks.ui.statistics

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.repository.ReadingRepositoryImpl
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h852dp-xhdpi")
class StatisticsScreenCrashTest {
    @get:Rule val compose = createComposeRule()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun fullScreenRendersWithRealViewModelAndSwitchesPeriods() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val book = Book(
            id = "book",
            title = "愤怒的葡萄",
            author = "作者",
            filePath = "/book.epub",
            coverPath = null,
            format = BookFormat.EPUB,
            lastReadTime = 0,
            readingProgress = 0.3f,
            createdAt = 1
        )
        val books = Proxy.newProxyInstance(
            BookRepository::class.java.classLoader,
            arrayOf(BookRepository::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAllBooks" -> flowOf(listOf(book))
                "getBookById" -> book.takeIf { args?.firstOrNull() == book.id }
                else -> error("Unexpected book repository call: ${method.name}")
            }
        } as BookRepository
        val repository = ReadingRepositoryImpl(
            db.readingRecordDao(),
            db.bookmarkDao(),
            db.noteDao(),
            db.syncStateDao(),
            SyncIdentityStore(db.syncStateDao()),
            db
        )
        val today = LocalDate.now()
        val start = today.atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.saveReadingSession(
            ReadingSession(
                sessionKey = "screen-session",
                bookId = book.id,
                date = today.toString(),
                startTime = start,
                endTime = start + 30 * 60_000L,
                duration = 30 * 60_000L
            )
        )
        val vm = StatisticsViewModel(repository, books, DataStoreManager(context))
        try {
            compose.setContent {
                EBookReaderTheme(appTheme = "lumi", darkTheme = false, lumiBackgroundScene = null) {
                    StatisticsScreen(playEntranceAnimation = false, viewModel = vm)
                }
            }
            compose.waitUntil(10_000) {
                !vm.uiState.value.weeklyLoading && !vm.uiState.value.sessionsLoading
            }
            compose.onNodeWithText("统计").assertIsDisplayed()
            compose.onNodeWithText("本周阅读时长").assertIsDisplayed()
            compose.onNodeWithText("月").performClick()
            compose.waitUntil(10_000) { !vm.uiState.value.monthlyLoading }
            compose.onNodeWithText("本月阅读时长").assertIsDisplayed()
            compose.onNodeWithText("年").performClick()
            compose.waitUntil(10_000) { !vm.uiState.value.yearlyLoading }
            compose.onNodeWithText("本年阅读时长").assertIsDisplayed()
            assertNull(vm.uiState.value.error)
        } finally {
            db.close()
        }
    }
}
