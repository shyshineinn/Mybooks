package com.huangder.lumibooks.ui.bookshelf

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w1000dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingTimelineLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var width by mutableStateOf(320.dp)
    private var scale by mutableStateOf(1f)
    private var theme by mutableStateOf("lumi")
    private var dark by mutableStateOf(false)
    private var ink by mutableStateOf(false)
    private lateinit var view: View
    private lateinit var context: Context
    private var mounts = 0
    private var backs = 0
    private val meta = ServerBookMeta.fromJson("""{
        "title":"Test book","status":"done","read_count":2,
        "read_logs":[{"start":"2026-10-27","finish":"2026-11-01"},{"start":"2026-11-04","finish":"2026-11-07"}]
    }""", 1)

    private fun mount(details: Boolean = false) {
        compose.setContent {
            view = LocalView.current
            context = LocalContext.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                EBookReaderTheme(appTheme = theme, darkTheme = dark, eInkMode = ink,
                    dynamicColor = theme == "material3", cardOutlinesEnabled = true,
                    motionPreference = MotionPreference.REDUCED) {
                    CompositionLocalProvider(LocalAppTheme provides if (ink) "lumi" else theme,
                        LocalLiquidGlassCapability provides LiquidGlassCapability(!ink, false)) {
                        Box(Modifier.width(width)) {
                            DisposableEffect(Unit) { mounts++; onDispose {} }
                            val events = readingTimelineEvents(meta)
                            if (details) {
                                ReadingTimelineDetailsScreen("Test book", meta, events, onBack = { backs++ })
                            } else {
                                ReadingRecordBlock(meta, events) {
                                    ReadingTimelineActivity.start(context, "book", "Test book")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun previewTexts() = listOf("阅读记录", "完成二刷·2026年11月7日", "开始二刷·2026年11月4日",
        "完成一刷·2026年11月1日", "本次历时 3 天", "间隔 3 天后再读")

    private fun assertTextFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            assertTrue("Clipped horizontal text: $text", layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f)
            assertTrue("Clipped vertical text: $text", layout.getLineBottom(line) <= layout.size.height + .5f)
        }
    }

    private fun capture(name: String, tag: String = "reading_timeline_preview") {
        val rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(screenshot)) }
        val crop = Bitmap.createBitmap(screenshot, rect.left.toInt(), rect.top.toInt(), rect.width.toInt(), rect.height.toInt())
        val output = File("build/reports/reading-timeline").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle(); screenshot.recycle()
    }

    @Test fun previewShowsThreeEventsAndOpensTheSameActivityTransitionAsReview() {
        mount()
        previewTexts().forEach(::assertTextFits)
        compose.onNodeWithText("开始一刷", substring = true).assertDoesNotExist()
        compose.onNodeWithText("查看全部").assertDoesNotExist()
        capture("preview-light")
        compose.onNodeWithTag("reading_timeline_preview").performClick()
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().first()
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(ReadingTimelineActivity::class.java.name, intent.component!!.className)
        assertEquals("book", intent.getStringExtra("bookId"))
        assertEquals("Test book", intent.getStringExtra("bookTitle"))
        val historyInfo = context.packageManager.getActivityInfo(ComponentName(context, ReadingTimelineActivity::class.java), 0)
        val reviewInfo = context.packageManager.getActivityInfo(ComponentName(context, BookReviewActivity::class.java), 0)
        assertEquals(reviewInfo.theme, historyInfo.theme)
        compose.onNodeWithTag("reading_timeline_preview").assertIsDisplayed()
        assertEquals(1, mounts)
    }

    @Test fun narrowLargeFontPreviewHasNoClippingAcrossThemes() {
        mount()
        listOf("lumi", "material3", "liquid_glass", "eink").forEach { appearance ->
            compose.runOnIdle { width = 240.dp; scale = 2f; dark = true; ink = appearance == "eink"; theme = appearance }
            previewTexts().forEach(::assertTextFits)
            val card = compose.onNodeWithTag("reading_timeline_preview").fetchSemanticsNode().boundsInRoot
            val rects = previewTexts().map {
                compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            }
            rects.forEach { assertTrue(it.left >= card.left && it.right <= card.right && it.bottom <= card.bottom) }
            rects.forEachIndexed { index, a -> rects.drop(index + 1).forEach { assertFalse(a.overlaps(it)) } }
            capture("preview-$appearance-large-font")
        }
    }

    @Test fun fullHistoryUsesASmallBottomAlignedBookNameAndKeepsBackOnTheSameRow() {
        mount(details = true)
        compose.onNodeWithTag("reading_timeline_list").performScrollToIndex(3)
        compose.onNodeWithText("开始一刷·2026年10月27日").assertIsDisplayed()
        fun verifyHeading() {
            val title = compose.onNodeWithTag("reading_timeline_title").fetchSemanticsNode().boundsInRoot
            val back = compose.onNodeWithTag("reading_timeline_back").fetchSemanticsNode().boundsInRoot
            val heading = compose.onNodeWithTag("reading_timeline_heading", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val name = compose.onNodeWithTag("reading_timeline_book_title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(back.center.y, title.center.y, .5f)
            assertTrue(title.left >= back.right)
            val body = compose.onNodeWithTag("reading_timeline_body").fetchSemanticsNode().boundsInRoot
            assertEquals("Match the shared 16dp header-to-content spacing", 16f * compose.density.density,
                body.top - maxOf(title.bottom, back.bottom), .5f)
            assertTrue("Book name should be smaller", name.height < heading.height)
            assertTrue("Book name must not be center aligned", name.center.y > heading.center.y)
            val headingLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("reading_timeline_heading", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(headingLayouts) }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("reading_timeline_book_title", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(heading.top + headingLayouts.single().firstBaseline,
                name.top + layouts.single().firstBaseline, .5f)
            assertEquals(AppType.Caption, layouts.single().layoutInput.style.fontSize)
            assertEquals(FontWeight.Normal, layouts.single().layoutInput.style.fontWeight)
            assertEquals(1, layouts.single().lineCount)
        }
        verifyHeading()
        capture("details-title", "reading_timeline_title")
        compose.runOnIdle { width = 240.dp; scale = 2f }
        verifyHeading()
        compose.onNodeWithTag("reading_timeline_back").performClick()
        assertEquals(1, backs)
    }
}
