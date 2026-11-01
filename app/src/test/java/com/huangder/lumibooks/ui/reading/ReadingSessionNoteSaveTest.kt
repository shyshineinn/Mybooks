package com.huangder.lumibooks.ui.reading

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.repository.ReadingRepositoryImpl
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.domain.model.ReadingSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingSessionNoteSaveTest {
    @Test fun noteAndMoodSaveOnlyWhenTheWriteSucceeds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = ReadingRepositoryImpl(
            db.readingRecordDao(),
            db.bookmarkDao(),
            db.noteDao(),
            db.syncStateDao(),
            SyncIdentityStore(db.syncStateDao()),
            db
        )
        try {
            val saved = repo.saveReadingSession(
                ReadingSession(
                    sessionKey = "note-session",
                    bookId = "book",
                    date = "2026-11-01",
                    startTime = 0,
                    endTime = 60_000,
                    duration = 60_000
                )
            )
            assertTrue(repo.updateReadingSessionNote(saved.id, "心得", "calm"))
            val updated = repo.getReadingSession(saved.id)!!
            assertEquals("心得", updated.note)
            assertEquals("calm", updated.mood)
            assertTrue(repo.updateReadingSessionNote(saved.id, null, null))
            val cleared = repo.getReadingSession(saved.id)!!
            assertNull(cleared.note)
            assertNull(cleared.mood)
        } finally {
            db.close()
        }
        assertFalse(repo.updateReadingSessionNote(1, "late", "calm"))
    }
}
