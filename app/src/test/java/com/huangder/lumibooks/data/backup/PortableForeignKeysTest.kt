package com.huangder.lumibooks.data.backup

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.entity.BookFolderCrossRefEntity
import com.huangder.lumibooks.data.local.entity.BookTagCrossRefEntity
import com.huangder.lumibooks.data.local.entity.FolderEntity
import com.huangder.lumibooks.data.local.entity.TagEntity
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.mineru.MineruTokenStore
import com.huangder.lumibooks.translation.TranslationTokenStore
import com.huangder.lumibooks.tts.ExternalTtsTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PortableForeignKeysTest {
    @Test fun linksForBooksThatAreNotPresentLocallyAreIgnoredInsteadOfFailing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = PortableSnapshotManager(context, db, DataStoreManager(context), SyncIdentityStore(db.syncStateDao()),
            MineruTokenStore(context), ExternalTtsTokenStore(context), TranslationTokenStore(context))
        try {
            db.bookDao().insertBook(BookEntity("mybooks-1", "Present", "Author", "", null, "EPUB", 0, 0f, createdAt = 1))
            db.syncStateDao().upsertFolders(listOf(FolderEntity("folder", "Folder", "folder", null, 1)))
            db.syncStateDao().upsertTags(listOf(TagEntity("tag", "Tag", "tag", 1)))
            val incoming = PortableSyncRules.empty("other").copy(
                books = emptyList(),
                folders = listOf(FolderEntity("folder", "Folder", "folder", null, 1)),
                tags = listOf(TagEntity("tag", "Tag", "tag", 1)),
                bookFolderLinks = listOf(
                    BookFolderCrossRefEntity("mybooks-404", "folder", 1),
                    BookFolderCrossRefEntity("mybooks-1", "folder", 2),
                ),
                bookTagLinks = listOf(
                    BookTagCrossRefEntity("mybooks-404", "tag", 1),
                    BookTagCrossRefEntity("mybooks-1", "tag", 2),
                ),
            )
            manager.apply(incoming, emptyMap(), replace = false, replaceAssetDirectories = false,
                preserveLocalBookIdentity = true, preserveNewerRows = true)
            assertEquals(listOf("mybooks-1"), db.syncStateDao().getAllBookFolderLinks().map { it.bookId })
            assertEquals(listOf("mybooks-1"), db.syncStateDao().getAllBookTagLinks().map { it.bookId })
        } finally { db.close() }
    }
}
