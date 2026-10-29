package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.huangder.lumibooks.ui.theme.EBookReaderTheme
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.components.ProvideLiquidGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.huangder.lumibooks.ui.theme.MotionPreference
import java.io.File
import org.junit.Rule
import org.junit.Test

class BookDetailsPagerInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(page: BookDetailsPage) = context.getString(page.titleRes)

    private fun setPager(
        theme: String,
        eInk: Boolean = false,
        dark: Boolean = false,
        reducedMotion: Boolean = false,
        rtl: Boolean = false,
        largeFont: Boolean = false,
        restoration: StateRestorationTester? = null
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            EBookReaderTheme(
                appTheme = theme,
                darkTheme = dark,
                dynamicColor = false,
                eInkMode = eInk,
                motionPreference = if (reducedMotion) MotionPreference.REDUCED else MotionPreference.STANDARD
            ) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (largeFont) 2f else density.fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.size(320.dp, 480.dp)) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(AppColors.PageBg))
                        ProvideLiquidGlassBackdrop(backdrop) {
                            BookDetailsPager("test-book", Modifier.fillMaxSize()) { page ->
                                items(50) { index ->
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
        if (restoration == null) composeRule.setContent(content)
        else restoration.setContent(content)
    }

    private fun assertPage(page: BookDetailsPage) {
        composeRule.onNodeWithText(label(page)).assertIsSelected()
        composeRule.onNodeWithTag("book_details_page_${page.name}").assertIsDisplayed()
    }

    private fun verifyTabs(theme: String, eInk: Boolean = false, dark: Boolean = false) {
        setPager(theme, eInk = eInk, dark = dark)
        assertPage(BookDetailsPage.Information)
        captureIfRequested("$theme-${if (eInk) "eink" else if (dark) "dark" else "light"}")
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).performClick()
        assertPage(BookDetailsPage.Files)
        composeRule.onNodeWithText(label(BookDetailsPage.Reading)).performClick()
        assertPage(BookDetailsPage.Reading)
    }

    @Test fun lumiTabsChangeContent() = verifyTabs("lumi")
    @Test fun materialTabsChangeContent() = verifyTabs("material3")
    @Test fun glassTabsChangeContent() = verifyTabs("liquid_glass")
    @Test fun darkLumiTabsChangeContent() = verifyTabs("lumi", dark = true)
    @Test fun darkMaterialTabsChangeContent() = verifyTabs("material3", dark = true)
    @Test fun darkGlassTabsChangeContent() = verifyTabs("liquid_glass", dark = true)

    @Test fun einkTabsChangeWithoutSwipeAnimation() {
        verifyTabs("lumi", eInk = true)
        composeRule.onNodeWithTag("book_details_tabs_eink").assertIsDisplayed()
        composeRule.onNodeWithTag("book_details_pager").performTouchInput { swipeLeft() }
        assertPage(BookDetailsPage.Reading)
    }

    @Test fun contentSwipeUpdatesSelectedTab() {
        setPager("liquid_glass")
        composeRule.onNodeWithTag("book_details_pager").performTouchInput { swipeLeft() }
        assertPage(BookDetailsPage.Reading)
        composeRule.onNodeWithTag("book_details_pager").performTouchInput { swipeRight() }
        assertPage(BookDetailsPage.Information)
    }

    @Test fun glassIndicatorCanStillBeDraggedToAnotherTab() {
        setPager("liquid_glass")
        composeRule.onNodeWithTag("book_details_tabs_glass").performTouchInput {
            swipe(
                start = Offset(width / 6f, height / 2f),
                end = Offset(width * 5f / 6f, height / 2f),
                durationMillis = 500
            )
        }
        assertPage(BookDetailsPage.Files)
    }

    @Test fun eachPageKeepsItsOwnScrollPosition() {
        setPager("lumi")
        composeRule.onNodeWithTag("book_details_page_Information").performScrollToIndex(20)
        composeRule.onNodeWithText("Information-20").assertIsDisplayed()
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).performClick()
        composeRule.onNodeWithTag("book_details_page_Files").performScrollToIndex(10)
        composeRule.onNodeWithText(label(BookDetailsPage.Information)).performClick()
        composeRule.onNodeWithText("Information-20").assertIsDisplayed()
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).performClick()
        composeRule.onNodeWithText("Files-10").assertIsDisplayed()
    }

    @Test fun selectedPageAndScrollSurviveStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        setPager("material3", restoration = restoration)
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).performClick()
        composeRule.onNodeWithTag("book_details_page_Files").performScrollToIndex(12)
        restoration.emulateSavedInstanceStateRestore()
        assertPage(BookDetailsPage.Files)
        composeRule.onNodeWithText("Files-12").assertIsDisplayed()
    }

    @Test fun largeFontRtlLumiTabsStayAccessible() {
        setPager("lumi", largeFont = true, rtl = true)
        captureIfRequested("lumi-rtl-large-font")
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).assertIsDisplayed().performClick()
        assertPage(BookDetailsPage.Files)
        composeRule.onNodeWithTag("book_details_pager").performTouchInput { swipeLeft() }
        assertPage(BookDetailsPage.Reading)
    }

    @Test fun largeFontMaterialTabsStayAccessible() {
        setPager("material3", largeFont = true)
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).assertIsDisplayed().performClick()
        assertPage(BookDetailsPage.Files)
    }

    @Test fun largeFontGlassTabsStayAccessible() {
        setPager("liquid_glass", largeFont = true)
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).assertIsDisplayed().performClick()
        assertPage(BookDetailsPage.Files)
    }

    @Test fun reducedMotionStillAllowsTabSelection() {
        setPager("liquid_glass", reducedMotion = true)
        composeRule.onNodeWithText(label(BookDetailsPage.Files)).performClick()
        assertPage(BookDetailsPage.Files)
    }

    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureDetailsTabs") != "true") return
        val directory = File(context.getExternalFilesDir(null), "details-tabs-preview").apply { mkdirs() }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
