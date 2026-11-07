package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookEntity
import com.huangder.lumibooks.data.local.entity.FolderEntity
import com.huangder.lumibooks.domain.model.BookTagLink
import com.huangder.lumibooks.domain.model.LibraryTag
import com.huangder.lumibooks.ui.bookshelf.bookshelfBrowseTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksOrganizationTest {
    @Test fun categoriesJoinTagBrowsingWithoutChangingEditableTags() {
        val tags = listOf(LibraryTag("tag", "文学", 1L))
        val links = listOf(BookTagLink("first", "tag"))
        val (browseTags, browseLinks) = bookshelfBrowseTags(tags, links, mapOf("first" to "文学", "second" to "文学", "third" to "小说"))
        assertEquals(listOf("文学", "小说"), browseTags.map { it.name })
        assertEquals(listOf("first", "second"), browseLinks.filter { it.tagId == "tag" }.map { it.bookId })
        assertEquals(listOf(BookTagLink("first", "tag")), links)
    }

    @Test fun directorySyncChangesVirtualMembershipAndPreservesStorageBindings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val bookId = "mybooks-1"
            val book = BookEntity(bookId, "Book", "Author", "/local/book.epub", null, "EPUB", 1L, .5f, "page", 1L)
            db.bookDao().insertBook(book)
            val folders = db.folderDao()
            db.applyMyBooksDirectory(bookId, "", "Library.Fiction")
            assertEquals("Library.Fiction", folders.myBooksDirectory(bookId))
            db.applyMyBooksDirectory(bookId, "", "LateReply")
            assertEquals("Library.Fiction", folders.myBooksDirectory(bookId))
            db.applyMyBooksDirectory(bookId, "Library.Fiction", "")
            assertEquals("", folders.myBooksDirectory(bookId))
            val longName = "a".repeat(24)
            db.applyMyBooksDirectory(bookId, "", longName)
            assertEquals(longName, folders.myBooksDirectory(bookId))
            folders.insertFolder(FolderEntity("physical", "Storage", "storage", null, 1L,
                storageDocumentUri = "content://storage/books"))
            folders.moveBooks(setOf(bookId), "physical")
            assertNull(folders.myBooksDirectory(bookId))
            db.applyMyBooksDirectory(bookId, "", "Library")
            assertEquals("physical", folders.getBookFolderId(bookId))
            assertEquals(book, db.bookDao().getBookById(bookId))
        } finally {
            db.close()
        }
    }
}
