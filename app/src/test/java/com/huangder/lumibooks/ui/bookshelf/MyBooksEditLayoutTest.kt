package com.huangder.lumibooks.ui.bookshelf

import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, qualifiers="en-rUS-w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyBooksEditLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val actions = Proxy.newProxyInstance(MyBooksEditActions::class.java.classLoader, arrayOf(MyBooksEditActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "MyBooksEditActionsTestDouble"
            else -> null
        }
    } as MyBooksEditActions
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
    private fun verify(width: Int, height: Int, importing: Boolean) {
        compose.setContent {
            Column(Modifier.size(width.dp, height.dp).background(AppColors.CardBg).padding(horizontal=AppSpace.lg, vertical=AppSpace.md)) {
                EditSheetContent(MyBooksEditUiState(loading=false, loaded=true, dirty=true, title="Book", author="Author", translators="甲、乙"),
                    createMode=importing, localImport=importing, onClose={}, onCancel={}, onSave={}, viewModel=actions)
            }
        }
        val title = if(importing) R.string.import_book_metadata_title else R.string.mybooks_edit_title
        val save = if(importing) R.string.import_book_metadata_confirm else R.string.save
        compose.onNodeWithText(text(title)).assertIsDisplayed()
        compose.onNodeWithText(text(save)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mybooks_edit_field_translators)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mybooks_edit_field_isbn)).performScrollTo().assertIsDisplayed()
        for(id in listOf(R.string.mybooks_edit_field_rating, R.string.mybooks_edit_field_publisher, R.string.mybooks_edit_field_pub_year, R.string.mybooks_edit_field_douban)) {
            compose.onNodeWithText(text(id)).assertDoesNotExist()
        }
        compose.onNodeWithText(text(R.string.mybooks_edit_field_summary)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mybooks_edit_section_basic)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.mybooks_edit_section_metadata)).assertDoesNotExist()
        compose.onNodeWithTag("mybooks-more-info").performScrollTo().performClick()
        // Expanding content may grow the sheet; scrolling must keep its actions pinned.
        val before = compose.onNodeWithText(text(save)).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(text(R.string.mybooks_edit_add_read)).performScrollTo().assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText(text(save)).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText(text(title)).assertIsDisplayed()
        compose.onNodeWithText(text(save)).assertIsDisplayed()
        val footer = compose.onNodeWithText(text(save)).fetchSemanticsNode().boundsInRoot
        assertTrue(footer.bottom <= height)
    }
    @Test fun narrowPhone() = verify(320, 640, false)
    @Test fun tablet() = verify(480, 800, false)
    @Test fun keyboardReducedViewport() = verify(360, 320, false)
    @Test fun importUsesTheSameFieldsAndPinnedActions() = verify(320, 360, true)

    @Test fun locallyImportedBookShowsItsTranslatorsAfterTheAuthor() {
        val book = Book(id="local-import", title="Book", author="Original Writer", filePath="", coverPath=null,
            format=BookFormat.EPUB, lastReadTime=0L, readingProgress=0f, createdAt=0L)
        val metadata = ServerBookMeta.fromJson(buildMyBooksCreatePayload(MyBooksEditUiState(title="Book", author="Author", translators="甲、乙")).toString(), 0)
        compose.setContent {
            Column(Modifier.size(480.dp, 800.dp).background(AppColors.CardBg)) {
                ContentInfoCards(book=book, folder="Folder", tags="", metaState=MyBooksMetaUiState(meta=metadata),
                    notesState=null, syncBadge=null, summaryExpanded=false, onSummaryExpandedChange={},
                    onEdit={}, onTags={}, onMove={}, onReview={})
            }
        }
        compose.onNodeWithText("甲、乙").assertIsDisplayed()
        val author = compose.onNodeWithText("Original Writer").fetchSemanticsNode().boundsInRoot
        val translators = compose.onNodeWithText("甲、乙").fetchSemanticsNode().boundsInRoot
        assertTrue(translators.top > author.top)
    }
}
