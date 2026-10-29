package com.huangder.lumibooks.ui.bookshelf

import android.app.UiAutomation
import android.content.res.Configuration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.huangder.lumibooks.R
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.BookFormat
import com.huangder.lumibooks.ui.components.ProvideLiquidGlassBackdrop
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Run on a landscape test device with at least 800 x 340dp of usable space. */
class BookDetailsLandscapeInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var windowWidth by mutableFloatStateOf(800f)
    private val book = Book(
        id = "landscape-preview", title = "远山与灯塔：一段漫长的旅程",
        author = "林间 · 一位名字很长的作者", filePath = "", coverPath = null,
        format = BookFormat.EPUB, lastReadTime = 0, readingProgress = 0.42f, createdAt = 0
    )

    @Before
    fun useLandscapeWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        instrumentation.waitForIdleSync()
    }

    @After
    fun releaseWindowRotation() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    private fun mount(
        theme: String = "lumi",
        dark: Boolean = false,
        eInk: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        restoration: StateRestorationTester? = null
    ) {
        val content: @Composable () -> Unit = {
            EBookReaderTheme(appTheme = theme, darkTheme = dark, dynamicColor = false, eInkMode = eInk) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize().background(AppColors.PageBg), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(windowWidth.dp, 340.dp)) {
                            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(AppColors.PageBg))
                            ProvideLiquidGlassBackdrop(backdrop) {
                                var summaryExpanded by rememberSaveable(book.id) { mutableStateOf(false) }
                                BookDetailsLayout(
                                    topBar = { showHero ->
                                        Row(
                                            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                if (showHero) context.getString(R.string.book_details) else book.title,
                                                color = AppColors.TextPrimary, maxLines = 1
                                            )
                                        }
                                    },
                                    hero = { twoPane, compact ->
                                        BookHero(book, "文学 / 旅行与随笔", "EPUB", compact, twoPane, onCover = {})
                                    },
                                    actions = {
                                        FloatingDetailTag(Modifier, {}, {}, {}, {}, {})
                                    }
                                ) { bottomOverlayHeight ->
                                    BookDetailsPager(
                                        book.id, Modifier.fillMaxSize(),
                                        bottomContentPadding = bottomOverlayHeight + 16.dp
                                    ) { page ->
                                        items(50) { index ->
                                            if (index == 0 && page == BookDetailsPage.Information) {
                                                Column(
                                                    Modifier.fillMaxWidth().clickable { summaryExpanded = !summaryExpanded }
                                                        .padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        if (summaryExpanded) "简介已展开" else "展开简介",
                                                        color = AppColors.TextPrimary
                                                    )
                                                    Text(
                                                        "旅行的记录，关于阅读、远方与日常。",
                                                        color = AppColors.TextSecondary
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    "${page.name}-$index",
                                                    color = AppColors.TextPrimary,
                                                    modifier = Modifier.height(48.dp).padding(8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
    }

    private fun select(page: BookDetailsPage) {
        composeRule.onNode(
            hasText(context.getString(page.titleRes)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        ).performClick().assertIsSelected()
        composeRule.onNodeWithTag("book_details_page_${page.name}").assertIsDisplayed()
    }

    private fun assertTwoPane(rtl: Boolean = false) {
        composeRule.onNodeWithTag("book_details_two_pane").assertIsDisplayed()
        val hero = composeRule.onNodeWithTag("book_details_hero").fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag("book_details_content").fetchSemanticsNode().boundsInRoot
        val footer = composeRule.onNodeWithTag("book_details_actions").fetchSemanticsNode().boundsInRoot
        assertTrue(if (rtl) hero.left >= content.right else hero.right <= content.left)
        assertTrue("Actions must float over the content", content.bottom > footer.top)
        assertTrue(kotlin.math.abs(content.bottom - footer.bottom) <= 1f)
        assertTrue("The content viewport must remain usable", content.height >= 100f)
        assertTrue(footer.left >= content.left - 1f && footer.right <= content.right + 1f)
        composeRule.onNode(
            hasText(context.getString(R.string.book_detail_read)) and
                hasAnyAncestor(hasTestTag("book_details_actions"))
        ).assertIsDisplayed()
    }

    private fun verify(theme: String, dark: Boolean = false, eInk: Boolean = false) {
        mount(theme, dark = dark, eInk = eInk)
        assertTwoPane()
        capture("$theme-${if (eInk) "eink" else if (dark) "dark" else "light"}")
        select(BookDetailsPage.Files)
        select(BookDetailsPage.Reading)
    }

    @Test fun lumiLandscape() = verify("lumi")
    @Test fun materialLandscape() = verify("material3")
    @Test fun glassLandscape() = verify("liquid_glass")
    @Test fun darkLumiLandscape() = verify("lumi", dark = true)
    @Test fun darkMaterialLandscape() = verify("material3", dark = true)
    @Test fun darkGlassLandscape() = verify("liquid_glass", dark = true)
    @Test fun einkLandscape() = verify("lumi", eInk = true)

    @Test fun rtlMirrorsBothPanes() {
        mount(rtl = true)
        assertTwoPane(rtl = true)
        select(BookDetailsPage.Files)
        capture("lumi-rtl")
    }

    @Test fun largeFontCanUseWideLandscape() {
        mount(fontScale = 2f)
        assertTwoPane()
        select(BookDetailsPage.Files)
        capture("lumi-large-font")
    }

    @Test fun largeFontNarrowWindowKeepsFloatingActions() {
        windowWidth = 680f
        mount(theme = "material3", fontScale = 2f)
        composeRule.onNodeWithTag("book_details_single_pane").assertIsDisplayed()
        select(BookDetailsPage.Files)
        val page = composeRule.onNodeWithTag("book_details_content").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("book_details_actions").fetchSemanticsNode().boundsInRoot
        assertTrue(page.bottom > actions.top)
        assertTrue(kotlin.math.abs(page.bottom - actions.bottom) <= 1f)
        capture("material3-large-font-fallback")
    }

    @Test fun windowResizeAndRecreationKeepPageScrollAndExpandedSummary() {
        val restoration = StateRestorationTester(composeRule)
        mount(restoration = restoration)
        composeRule.onNodeWithText("展开简介").performClick()
        composeRule.onNodeWithTag("book_details_page_Information").performScrollToIndex(20)
        select(BookDetailsPage.Files)
        composeRule.onNodeWithTag("book_details_page_Files").performScrollToIndex(10)
        composeRule.runOnIdle { windowWidth = 320f }
        composeRule.onNodeWithTag("book_details_single_pane").assertIsDisplayed()
        composeRule.onNodeWithText("Files-10").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(context.getString(BookDetailsPage.Files.titleRes)).assertIsSelected()
        composeRule.onNodeWithText("Files-10").assertIsDisplayed()
        composeRule.runOnIdle { windowWidth = 800f }
        assertTwoPane()
        composeRule.onNodeWithText("Files-10").assertIsDisplayed()
        select(BookDetailsPage.Information)
        composeRule.onNodeWithText("Information-20").assertIsDisplayed()
        composeRule.onNodeWithTag("book_details_page_Information").performScrollToIndex(0)
        capture("restored-summary")
        composeRule.onNodeWithText("简介已展开").assertIsDisplayed()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDetailsTabs") != "true") return
        val directory = File(context.getExternalFilesDir(null), "details-landscape-preview").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
