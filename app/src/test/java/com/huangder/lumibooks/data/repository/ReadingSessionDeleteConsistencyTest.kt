package com.huangder.lumibooks.data.repository

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.ReadingRecordEntity
import com.huangder.lumibooks.data.local.entity.ReadingSessionEntity
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.domain.model.ReadingRecord
import com.huangder.lumibooks.domain.model.ReadingSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingSessionDeleteConsistencyTest {
    private fun repository(db: AppDatabase) = ReadingRepositoryImpl(
        db.readingRecordDao(),
        db.bookmarkDao(),
        db.noteDao(),
        db.syncStateDao(),
        SyncIdentityStore(db.syncStateDao()),
        db
    )

    private fun wallTime(date: String): Long =
        LocalDate.parse(date).atTime(1, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun session(
        date: String,
        duration: Long = 30_000L,
        start: Long = wallTime(date),
        end: Long = start + duration,
        key: String = "session-$date-$start"
    ) = ReadingSession(
        sessionKey = key,
        bookId = "book",
        date = date,
        startTime = start,
        endTime = end,
        duration = duration
    )

    @Test fun deletingSessionRemovesItsDailyContribution() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val saved = repo.saveReadingSession(session("2026-11-01"))
            assertEquals(30_000L, repo.getTotalDurationByDate("2026-11-01").first())
            val deleted = repo.deleteReadingSession(saved.id)
            assertNotNull(deleted)
            assertEquals(0L, repo.getTotalDurationByDate("2026-11-01").first())
            assertNull(repo.deleteReadingSession(saved.id))
        } finally {
            db.close()
        }
    }

    @Test fun legacyDailyTotalsSurviveSessionDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val date = "2026-11-02"
            repo.insertRecord(
                ReadingRecord(bookId = "book", date = date, duration = 60_000L, startTime = 1L, endTime = 2L)
            )
            val saved = repo.saveReadingSession(session(date, duration = 30_000L))
            assertEquals(90_000L, repo.getTotalDurationByDate(date).first())
            repo.deleteReadingSession(saved.id)
            assertEquals(60_000L, repo.getTotalDurationByDate(date).first())
        } finally {
            db.close()
        }
    }

    @Test fun repeatedCheckpointDoesNotDoubleCountTheDailyAggregate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val first = repo.saveReadingSession(session("2026-11-03", key = "stable-key"))
            val second = repo.saveReadingSession(session("2026-11-03", key = "stable-key"))
            assertEquals(first.id, second.id)
            assertEquals(1, repo.observeReadingSessionsByBook("book").first().size)
            assertEquals(30_000L, repo.getTotalDurationByDate("2026-11-03").first())
        } finally {
            db.close()
        }
    }

    @Test fun crossMidnightContributionIsSplitAndDeletedOnBothDays() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val zone = ZoneId.systemDefault()
            val firstDay = LocalDate.of(2026, 11, 4)
            val start = firstDay.atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
            val end = firstDay.plusDays(1).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
            val saved = repo.saveReadingSession(
                ReadingSession(
                    sessionKey = "cross-midnight",
                    bookId = "book",
                    date = firstDay.toString(),
                    startTime = start,
                    endTime = end,
                    duration = 20 * 60_000L
                )
            )
            val firstTotal = repo.getTotalDurationByDate("2026-11-04").first() ?: 0L
            val secondTotal = repo.getTotalDurationByDate("2026-11-05").first() ?: 0L
            assertEquals(20 * 60_000L, firstTotal + secondTotal)
            assertTrue(firstTotal > 0L && secondTotal > 0L)
            repo.deleteReadingSession(saved.id)
            assertEquals(0L, (repo.getTotalDurationByDate("2026-11-04").first() ?: 0L) + (repo.getTotalDurationByDate("2026-11-05").first() ?: 0L))
        } finally {
            db.close()
        }
    }

    @Test fun deletingOneDeviceDoesNotTouchAnotherDevicesDailyTotal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val date = "2026-11-06"
            db.readingRecordDao().insertRecord(
                ReadingRecordEntity(
                    bookId = "book",
                    date = date,
                    duration = 60_000L,
                    startTime = 1L,
                    endTime = 2L,
                    sourceDeviceId = "other-device",
                    updatedAt = 2L
                )
            )
            val saved = repo.saveReadingSession(session(date, duration = 30_000L))
            repo.deleteReadingSession(saved.id)
            assertEquals(60_000L, repo.getTotalDurationByDate(date).first())
        } finally {
            db.close()
        }
    }

    @Test fun shortRawSessionIsStoredWithoutChangingDailyTotals() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val saved = repo.saveReadingSession(session("2026-11-07", duration = 10_000L))
            assertFalse(saved.isEffective)
            assertEquals(1, repo.observeReadingSessionsByBook("book").first().size)
            assertEquals(0L, repo.getTotalDurationByDate("2026-11-07").first() ?: 0L)
        } finally {
            db.close()
        }
    }

    @Test fun oldSessionsWithoutContributionJsonStillSubtractTheirRecordedTime() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = repository(db)
            val date = "2026-11-08"
            repo.insertRecord(
                ReadingRecord(bookId = "book", date = date, duration = 30_000L, startTime = 1L, endTime = 2L)
            )
            val sessionId = db.readingSessionDao().insertSession(
                ReadingSessionEntity(
                    bookId = "book",
                    date = date,
                    startTime = 1L,
                    endTime = 2L,
                    duration = 30_000L,
                    contributedDuration = 30_000L,
                    contributionsJson = "{}",
                    updatedAt = 2L
                )
            )
            repo.deleteReadingSession(sessionId)
            assertEquals(0L, repo.getTotalDurationByDate(date).first() ?: 0L)
        } finally {
            db.close()
        }
    }
}
