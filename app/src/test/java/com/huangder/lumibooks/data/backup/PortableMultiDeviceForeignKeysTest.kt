package com.huangder.lumibooks.data.backup

import android.app.Application
import androidx.room.Room
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.*
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
class PortableMultiDeviceForeignKeysTest {
    private fun manager(db: AppDatabase): PortableSnapshotManager {
        val context = RuntimeEnvironment.getApplication()
        return PortableSnapshotManager(context, db, DataStoreManager(context), SyncIdentityStore(db.syncStateDao()),
            MineruTokenStore(context), ExternalTtsTokenStore(context), TranslationTokenStore(context))
    }

    @Test fun sameNameTagMergeIsSymmetricAndRetainsLinksAndChildParents() {
        val local = PortableSyncRules.empty("first-device").copy(
            tags = listOf(TagEntity("local-tag", "Shared", "shared", 1),
                TagEntity("child", "Child", "child", 1, parentId = "local-tag")),
            bookTagLinks = listOf(BookTagCrossRefEntity("first", "local-tag", 1)))
        val remote = PortableSyncRules.empty("second-device").copy(
            tags = listOf(TagEntity("remote-tag", "Shared", "shared", 2)),
            bookTagLinks = listOf(BookTagCrossRefEntity("second", "remote-tag", 2)))
        val books = listOf("first", "second").map { id ->
            PortableBook(id, id, "", "EPUB", 0, 0f, null, 1, false, true, 1,
                null, null, null, null, 0, null)
        }
        val merged = PortableSnapshotMerger.merge(local.copy(books = books), remote.copy(books = books))
        assertEquals(setOf("first", "second"), merged.bookTagLinks.map { it.bookId }.toSet())
        val first = mergePortableTags(local.tags + remote.tags, local.bookTagLinks + remote.bookTagLinks)
        val second = mergePortableTags(remote.tags + local.tags, remote.bookTagLinks + local.bookTagLinks)
        assertEquals(first.tags.toSet(), second.tags.toSet())
        assertEquals(first.links.toSet(), second.links.toSet())
        assertEquals(setOf("first", "second"), first.links.map { it.bookId }.toSet())
        assertEquals(setOf("remote-tag"), first.links.map { it.tagId }.toSet())
        assertEquals("remote-tag", first.tags.single { it.id == "child" }.parentId)
        val repeated = mergePortableTags(first.tags + local.tags, first.links + local.bookTagLinks)
        assertEquals(first.tags.toSet(), repeated.tags.toSet())
        assertEquals(first.links.toSet(), repeated.links.toSet())
    }
    @Test fun existingFolderCanMoveUnderANewRemoteParent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val child = FolderEntity("child", "Child", "child", null, 1)
            db.syncStateDao().upsertFolders(listOf(child))
            val parent = FolderEntity("parent", "Parent", "parent", null, 2)
            val incoming = PortableSyncRules.empty("second-device").copy(folders = listOf(
                child.copy(parentId = parent.id, updatedAt = 3), parent))
            manager(db).apply(incoming, emptyMap(), replace = false, preserveNewerRows = true)
            assertEquals("parent", db.syncStateDao().getAllFolders().single { it.id == "child" }.parentId)
        } finally { db.close() }
    }

    @Test fun independentlyCreatedSameNameTagsKeepBothBookAssignments() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            db.bookDao().insertBook(BookEntity("first", "First", "", "", null, "EPUB", 0, 0f, createdAt = 1))
            db.bookDao().insertBook(BookEntity("second", "Second", "", "", null, "EPUB", 0, 0f, createdAt = 1))
            db.syncStateDao().upsertTags(listOf(TagEntity("local-tag", "Shared", "shared", 1)))
            db.syncStateDao().upsertBookTagLinks(listOf(BookTagCrossRefEntity("first", "local-tag", 1)))
            val incoming = PortableSyncRules.empty("second-device").copy(
                tags = listOf(TagEntity("remote-tag", "Shared", "shared", 2)),
                bookTagLinks = listOf(BookTagCrossRefEntity("second", "remote-tag", 2)))
            manager(db).apply(incoming, emptyMap(), replace = false, preserveNewerRows = true)
            // Retrying an older payload must neither duplicate tags nor lose local links.
            manager(db).apply(incoming, emptyMap(), replace = false, preserveNewerRows = true)
            val tags = db.syncStateDao().getAllTags()
            assertEquals(1, tags.size)
            assertEquals(setOf("first", "second"), db.syncStateDao().getAllBookTagLinks().map { it.bookId }.toSet())
            assertEquals(setOf(tags.single().id), db.syncStateDao().getAllBookTagLinks().map { it.tagId }.toSet())
        } finally { db.close() }
    }
}