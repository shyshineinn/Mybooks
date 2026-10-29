package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.MotionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1000dp-h1000dp")
class BookDetailsFloatingActionsTest {
    @get:Rule val compose = createComposeRule()
    private var width by mutableStateOf(400.dp)
    private var height by mutableStateOf(800.dp)
    private var actionHeight by mutableStateOf(64.dp)
    private var rtl by mutableStateOf(false)

    private fun mount() {
        compose.setContent {
            EBookReaderTheme(darkTheme = false, motionPreference = MotionPreference.REDUCED) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Box(Modifier.size(width, height)) {
                        BookDetailsLayout(
                            topBar = { Box(Modifier.height(40.dp)) },
                            hero = { _, _ -> Box(Modifier.height(96.dp)) },
                            actions = {
                                Box(Modifier.width(240.dp).height(actionHeight).background(Color.Gray))
                            }
                        ) { bottomOverlayHeight ->
                            BookDetailsPager(
                                "floating-actions-regression", Modifier.fillMaxSize(),
                                bottomContentPadding = bottomOverlayHeight + 16.dp
                            ) { page ->
                                items(40) { index ->
                                    Text("${page.name}-$index", modifier = Modifier.height(48.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertFloating() {
        val content = compose.onNodeWithTag("book_details_content").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("book_details_actions").fetchSemanticsNode().boundsInRoot
        assertTrue("The content must extend beneath the actions", content.bottom > actions.top)
        assertEquals(content.bottom, actions.bottom, 1f)
        assertTrue(actions.left >= content.left - 1f && actions.right <= content.right + 1f)
    }

    private fun assertLastItemClearsActions(page: BookDetailsPage) {
        compose.onNodeWithTag("book_details_page_${page.name}").performScrollToIndex(39)
        val lastItem = compose.onNodeWithText("${page.name}-39").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("book_details_actions").fetchSemanticsNode().boundsInRoot
        assertTrue("The final row must scroll above the floating actions", lastItem.bottom <= actions.top + 1f)
    }

    @Test
    fun contentScrollsBehindActionsAndTheLastRowRemainsReachableInEachLayout() {
        mount()
        assertFloating()
        assertLastItemClearsActions(BookDetailsPage.Information)
        compose.runOnIdle { width = 800.dp; height = 360.dp; rtl = true }
        compose.onNodeWithTag("book_details_two_pane").assertIsDisplayed()
        assertFloating()
        assertLastItemClearsActions(BookDetailsPage.Information)
        compose.runOnIdle { width = 320.dp; actionHeight = 96.dp }
        compose.onNodeWithTag("book_details_single_pane").assertIsDisplayed()
        assertFloating()
        assertLastItemClearsActions(BookDetailsPage.Information)
    }

    @Test
    fun measuredActionHeightChangesPreserveTheSelectedPageAndScrollPosition() {
        mount()
        compose.onNodeWithTag("book_details_tab_2").performClick().assertIsSelected()
        compose.onNodeWithTag("book_details_page_Files").performScrollToIndex(10)
        compose.runOnIdle { actionHeight = 104.dp; width = 800.dp; height = 360.dp }
        compose.onNodeWithTag("book_details_tab_2").assertIsSelected()
        compose.onNodeWithText("Files-10").assertIsDisplayed()
        assertFloating()
        assertLastItemClearsActions(BookDetailsPage.Files)
        compose.onNodeWithTag("book_details_tab_1").performClick().assertIsSelected()
        assertLastItemClearsActions(BookDetailsPage.Reading)
    }
}
