package com.huangder.lumibooks.ui.statistics

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.repository.ReadingRepositoryImpl
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.domain.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StatisticsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun weeklyMonthlyAndYearlyObserversEmitWithoutErrors() = runBlocking {
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
        try {
            val today = LocalDate.now()
            val start = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.saveReadingSession(
                ReadingSession(
                    sessionKey = "statistics-session",
                    bookId = book.id,
                    date = today.toString(),
                    startTime = start,
                    endTime = start + 30 * 60_000L,
                    duration = 30 * 60_000L,
                    startProgress = 0.2f,
                    endProgress = 0.3f
                )
            )
            val vm = StatisticsViewModel(repository, books, DataStoreManager(context))
            val week = withTimeout(10_000) {
                vm.uiState.first { !it.weeklyLoading && !it.sessionsLoading && !it.recentSessionsLoading }
            }
            assertEquals(7, week.weeklyData.size)
            assertNull(week.error)

            vm.selectTab(1)
            val month = withTimeout(10_000) {
                vm.uiState.first { it.selectedTab == 1 && !it.monthlyLoading }
            }
            assertNull(month.error)

            vm.selectTab(2)
            val year = withTimeout(10_000) {
                vm.uiState.first { it.selectedTab == 2 && !it.yearlyLoading }
            }
            assertNull(year.error)

            vm.previousYear()
            vm.selectTab(0)
            vm.previousWeek()
            vm.nextWeek()
            vm.selectTab(1)
            vm.previousMonth()
            vm.nextMonth()
            assertEquals(1, vm.uiState.value.selectedTab)
        } finally {
            db.close()
        }
    }
}
