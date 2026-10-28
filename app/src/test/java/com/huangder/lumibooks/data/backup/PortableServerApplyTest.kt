package com.huangder.lumibooks.data.backup

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.*
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.domain.model.SyncContentSelection
import com.huangder.lumibooks.mineru.MineruTokenStore
import com.huangder.lumibooks.translation.TranslationTokenStore
import com.huangder.lumibooks.tts.ExternalTtsTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PortableServerApplyTest {
    @Test fun applyingServerOrganizationKeepsActualBodyLatestReadingAndNewerFolderEdit() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = PortableSnapshotManager(context, db, DataStoreManager(context), SyncIdentityStore(db.syncStateDao()),
            MineruTokenStore(context), ExternalTtsTokenStore(context), TranslationTokenStore(context))
        try {
            db.bookDao().insertBook(BookEntity("mybooks-1", "Current", "Author", "/local/book.epub", null, "EPUB", 99, .8f,
                "latest-position", 1, sourceUri = "mybooks:1", sourceSha256 = "a".repeat(64), metadataUpdatedAt = 9))
            db.syncStateDao().upsertFolders(listOf(FolderEntity("f", "Edited during request", "edited during request", null, 1, updatedAt = 9)))
            val book = PortableBook("mybooks-1", "Stale", "Author", "EPUB", 1, .1f, "stale-position", 1, false, true, 1, null, null, null, null, 0, null)
            val incoming = PortableSyncRules.empty("other").copy(books = listOf(book),
                folders = listOf(FolderEntity("f", "Old", "old", null, 1)),
                tombstones = listOf(SyncTombstoneEntity("folder", "f", 2, "other")))
            manager.apply(incoming, emptyMap(), replace = false, replaceAssetDirectories = false,
                preserveLocalBookIdentity = true, preserveNewerRows = true)
            val actual = db.bookDao().getBookById("mybooks-1")!!
            assertEquals("/local/book.epub", actual.filePath); assertEquals("mybooks:1", actual.sourceUri)
            assertEquals("a".repeat(64), actual.sourceSha256); assertFalse(actual.isCloudOnly)
            assertEquals("latest-position", actual.locatorJson); assertEquals(99L, actual.lastReadTime)
            assertEquals(.8f, actual.readingProgress); assertEquals("Current", actual.title)
            assertEquals("Edited during request", db.syncStateDao().getAllFolders().single().name)
        } finally { db.close() }
    }
    @Test fun disabledProfileRetainsItsLocalResourceWhileLibraryChanges() {
        val asset = PortableAsset("a".repeat(64), "avatar", "profile", "a.jpg", "a".repeat(64), 1)
        val local = PortableSyncRules.empty("local").copy(preferences = listOf(PortablePreference("avatar_path", "string", "asset://${asset.id}", 1, "local")), assets = listOf(asset))
        val bundle = PortableSyncRules.filterBundle(PortableSnapshotBundle(local, emptyMap()), SyncContentSelection(profileAndSettings = false))
        val remote = PortableSyncRules.empty("remote").copy(folders = listOf(FolderEntity("f", "Remote", "remote", null, 1)))
        val applied = PortableSyncRules.forLocal(bundle.snapshot, remote, SyncContentSelection(profileAndSettings = false))
        assertEquals(local.preferences, applied.preferences); assertTrue(applied.assets.contains(asset))
        assertEquals("f", applied.folders.single().id)
    }
}
