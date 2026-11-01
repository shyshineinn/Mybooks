package com.huangder.lumibooks.ui.reading

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.R
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class ReadingHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun defaultFilterShowsAllTimeAndPagedQueriesRespectTheLimit() = runBlocking {
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
            readingProgress = 0f,
            createdAt = 1
        )
        val books = Proxy.newProxyInstance(
            BookRepository::class.java.classLoader,
            arrayOf(BookRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAllBooks" -> flowOf(listOf(book))
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
            val base = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repeat(120) { index ->
                repository.saveReadingSession(
                    ReadingSession(
                        sessionKey = "session-$index",
                        bookId = book.id,
                        date = today.toString(),
                        startTime = base + index * 60_000L,
                        endTime = base + index * 60_000L + 30_000L,
                        duration = 30_000L
                    )
                )
            }
            val vm = ReadingHistoryViewModel(repository, books)
            val initial = vm.uiState.first { !it.loading }
            assertEquals(ReadingHistoryPeriod.ALL, initial.filter.period)
            assertEquals(R.string.reading_history_period_all, initial.filter.period.labelRes)
            assertEquals(100, initial.sessions.size)
            assertTrue(initial.hasMore)

            vm.loadMore()
            val expanded = vm.uiState.first { it.sessions.size >= 120 }
            assertEquals(120, expanded.sessions.size)

            vm.applyFilter(ReadingHistoryFilter(period = ReadingHistoryPeriod.WEEK))
            val weekly = vm.uiState.first {
                it.filter.period == ReadingHistoryPeriod.WEEK && !it.loading
            }
            assertEquals(100, weekly.sessions.size)
            assertTrue(weekly.hasMore)
        } finally {
            db.close()
        }
    }
}
