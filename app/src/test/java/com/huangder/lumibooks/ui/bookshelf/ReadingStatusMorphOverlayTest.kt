package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import com.huangder.lumibooks.ui.theme.AppColors
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.domain.model.MenuAnimationStyle
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.theme.LiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalMenuAnimationStyle
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import com.huangder.lumibooks.ui.theme.LocalUseMaterial3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w640dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingStatusMorphOverlayTest {
    @get:Rule val compose = createComposeRule()

    private val open = mutableStateOf(false)
    private val close = mutableStateOf(false)
    private val saving = mutableStateOf(false)
    private val selected = mutableStateOf(ReadingStatus.WISH)
    private val picked = mutableStateOf<ReadingStatus?>(null)
    private val rowBounds = mutableStateOf(Rect.Zero)
    private val tapOrigin = mutableStateOf(Offset.Zero)

    private fun statusLabel(status: ReadingStatus): String = when (status) {
        ReadingStatus.WISH -> "Wish"
        ReadingStatus.WANT_TO_READ -> "Want to read"
        ReadingStatus.READING -> "Reading"
        ReadingStatus.DONE -> "Read"
    }

    private fun runScenario(theme: String, motionEnabled: Boolean, menuStyle: MenuAnimationStyle = MenuAnimationStyle.LIQUID) {
        compose.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides theme,
                LocalUseMaterial3Theme provides (theme == "material3"),
                LocalMotionEnabled provides motionEnabled,
                LocalMenuAnimationStyle provides menuStyle,
                LocalLiquidGlassCapability provides if (theme == "liquid_glass") {
                    LiquidGlassCapability(true, false)
                } else {
                    LocalLiquidGlassCapability.current
                }
            ) {
                Box(Modifier.fillMaxSize().background(Color.LightGray)) {
                    Column(Modifier.fillMaxSize()) {
                        Text("Book title", Modifier.padding(16.dp).testTag("book-title"))
                        Text("Author", Modifier.padding(16.dp).testTag("book-author"))
                        Spacer(Modifier.height(280.dp))
                        ReadingStatusDetailRow(
                            label = "Status",
                            value = statusLabel(selected.value),
                            clickable = true,
                            onClick = {},
                            onBounds = { rowBounds.value = it },
                            hidden = open.value,
                            onTapAt = { local ->
                                tapOrigin.value = rowBounds.value.topLeft + local
                                open.value = true
                            },
                            modifier = Modifier.testTag("status-row")
                        )
                        Text("Rating", Modifier.padding(16.dp).testTag("book-rating"))
                        Spacer(Modifier.weight(1f))
                        Text("Library info", Modifier.padding(16.dp).testTag("library-info"))
                    }
                    if (open.value) {
                        ReadingStatusMorphOverlay(
                            anchor = ReadingStatusMorphAnchor(
                                statusRowBounds = rowBounds.value,
                                originCenter = tapOrigin.value,
                                status = selected.value
                            ),
                            selected = selected.value,
                            saving = saving.value,
                            closeRequested = close.value,
                            onSelect = {
                                if (!saving.value) {
                                    picked.value = it
                                    selected.value = it
                                    saving.value = true
                                }
                            },
                            onDismiss = { close.value = true },
                            onFinished = {
                                open.value = false
                                close.value = false
                                saving.value = false
                            }
                        )
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("status-row").performTouchInput { click(Offset(160f, 24f)) }
        compose.waitForIdle()

        compose.onNodeWithTag("book-title").assertIsDisplayed()
        compose.onNodeWithTag("book-author").assertIsDisplayed()
        compose.onNodeWithTag("book-rating").assertIsDisplayed()
        compose.onNodeWithTag("library-info").assertIsDisplayed()
        compose.onNodeWithTag("reading-status-option-wish").assertExists()
        compose.onNodeWithTag("reading-status-option-reading").assertExists()
        compose.onNodeWithTag("reading-status-option-done").assertExists()

        val card = compose.onNodeWithTag("liquid-glass-morph-card")
            .fetchSemanticsNode().boundsInRoot
        val widthDp = card.width / compose.density.density
        // Width can grow to fit translated status labels and reread actions.
        assertTrue("selector width was ${widthDp}dp", widthDp in 102f..200f)
        assertEquals(tapOrigin.value.x, card.center.x, 2f)
        assertEquals(tapOrigin.value.y, card.center.y, 2f)

        compose.onNodeWithTag("reading-status-option-reading").performClick()
        compose.waitForIdle()
        assertEquals(ReadingStatus.READING, picked.value)
        assertTrue(saving.value)
        compose.onNodeWithTag("reading-status-option-done").assertIsNotEnabled()

        compose.runOnIdle { close.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("liquid-glass-morph-card").assertDoesNotExist()
        compose.onNodeWithTag("status-row").assertExists()
        compose.onNodeWithTag("book-title").assertIsDisplayed()
        compose.onNodeWithText("Reading").assertExists()
    }

    @Test
    fun `lumi theme keeps the page visible and centers the selector on the tap`() =
        runScenario(theme = "lumi", motionEnabled = true)

    @Test
    fun `material3 theme keeps the page visible and centers the selector on the tap`() =
        runScenario(theme = "material3", motionEnabled = true)

    @Test
    fun `liquid glass theme keeps the page visible and centers the selector on the tap`() =
        runScenario(theme = "liquid_glass", motionEnabled = true)

    @Test
    fun `normal menu style still centers the selector on the tap`() =
        runScenario(theme = "lumi", motionEnabled = true, menuStyle = MenuAnimationStyle.NORMAL)

    @Test
    fun `reduced motion still opens and closes the selector at the tap`() =
        runScenario(theme = "lumi", motionEnabled = false)

    @Test
    fun wantToReadIsOneOfFourExclusiveChoices() {
        val choice = mutableStateOf(ReadingStatus.WISH)
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                Box(Modifier.fillMaxSize()) {
                    ReadingStatusMorphOverlay(
                        anchor = ReadingStatusMorphAnchor(Rect(20f, 300f, 600f, 348f), Offset(310f, 324f), ReadingStatus.WISH),
                        selected = choice.value,
                        saving = false,
                        closeRequested = false,
                        onSelect = { choice.value = it },
                        onDismiss = {},
                        onFinished = {},
                        showWantToRead = true
                    )
                }
            }
        }
        val wanted = compose.onNodeWithTag("reading-status-option-want_to_read")
        val unread = compose.onNodeWithTag("reading-status-option-wish")
        wanted.assertIsDisplayed().assertIsNotSelected().performClick().assertIsSelected()
        unread.assertIsNotSelected().performClick().assertIsSelected()
        wanted.assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h900dp-mdpi")
    fun rereadAndAbandonActionsStayReachableInAShortScrollablePopup() {
        val next = mutableStateOf<Int?>(2)
        val active = mutableStateOf<Int?>(null)
        var starts = 0
        var abandons = 0
        var normalColor = Color.Unspecified
        fun assertActionColor(label: String) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(normalColor, layouts.single().layoutInput.style.color)
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalMotionEnabled provides false
            ) {
                normalColor = AppColors.TextPrimary
                Box(Modifier.size(240.dp, 220.dp)) {
                    ReadingStatusMorphOverlay(
                        anchor = ReadingStatusMorphAnchor(Rect(0f, 60f, 240f, 110f), Offset(120f, 85f), ReadingStatus.DONE),
                        selected = if (active.value == null) ReadingStatus.DONE else ReadingStatus.READING,
                        saving = saving.value, closeRequested = false, onSelect = {}, onDismiss = {}, onFinished = {},
                        nextRound = next.value, activeRound = active.value,
                        onStartNextReading = { starts++ }, onAbandonReading = { abandons++ }
                    )
                }
            }
        }
        val card = compose.onNodeWithTag("liquid-glass-morph-card").fetchSemanticsNode().boundsInRoot
        assertTrue(card.top >= 0f && card.bottom <= 220f)
        compose.onNodeWithTag("reading-status-abandon").assertDoesNotExist()
        compose.onNodeWithTag("reading-status-start-next").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, starts)
        assertActionColor("开始二刷")
        compose.runOnIdle { next.value = null; active.value = 2 }
        compose.onNodeWithTag("reading-status-start-next").assertDoesNotExist()
        compose.onNodeWithText("放弃二刷").assertExists()
        compose.onNodeWithTag("reading-status-abandon").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, abandons)
        assertActionColor("放弃二刷")
        compose.runOnIdle { saving.value = true }
        compose.onNodeWithTag("reading-status-abandon").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h900dp-mdpi")
    fun rereadActionKeepsNormalWeightAndColorWhenThePopupBecomesInteractive() {
        val show = mutableStateOf(false)
        var normalColor = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(
                LocalMotionEnabled provides true,
                LocalMenuAnimationStyle provides MenuAnimationStyle.NORMAL
            ) {
                normalColor = AppColors.TextPrimary
                Box(Modifier.fillMaxSize()) {
                    if (show.value) ReadingStatusMorphOverlay(
                        anchor = ReadingStatusMorphAnchor(Rect(20f, 300f, 600f, 348f), Offset(310f, 324f), ReadingStatus.DONE),
                        selected = ReadingStatus.DONE, saving = false, closeRequested = false,
                        onSelect = {}, onDismiss = {}, onFinished = {}, nextRound = 2
                    )
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { show.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        fun actionLayout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("开始二刷", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        compose.onNodeWithTag("reading-status-start-next", useUnmergedTree = true).assertIsNotEnabled()
        val opening = actionLayout()
        assertEquals(FontWeight.Normal, opening.layoutInput.style.fontWeight)
        assertEquals(normalColor, opening.layoutInput.style.color)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithTag("reading-status-start-next").assertIsEnabled()
        val ready = actionLayout()
        assertEquals(opening.layoutInput.style.fontWeight, ready.layoutInput.style.fontWeight)
        assertEquals(opening.layoutInput.style.color, ready.layoutInput.style.color)
        assertEquals(opening.size, ready.size)
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h900dp-mdpi")
    fun defaultViewportShowsThreeStatusesAndExtraActionsRequireScrolling() {
        val visible = mutableStateOf(true)
        val active = mutableStateOf<Int?>(null)
        var starts = 0
        var abandons = 0
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                Box(Modifier.fillMaxSize()) {
                    if (visible.value) ReadingStatusMorphOverlay(
                        anchor = ReadingStatusMorphAnchor(Rect(20f, 300f, 600f, 348f), Offset(310f, 324f), ReadingStatus.DONE),
                        selected = if (active.value == null) ReadingStatus.DONE else ReadingStatus.READING,
                        saving = false, closeRequested = false, onSelect = {}, onDismiss = {}, onFinished = {},
                        nextRound = if (active.value == null) 2 else null, activeRound = active.value,
                        onStartNextReading = { starts++ }, onAbandonReading = { abandons++ }
                    )
                }
            }
        }
        fun assertDefaultViewport() {
            listOf("wish", "reading", "done").forEach {
                compose.onNodeWithTag("reading-status-option-$it").assertIsDisplayed()
            }
            val bounds = compose.onNodeWithTag("liquid-glass-morph-card").fetchSemanticsNode().boundsInRoot
            assertEquals("Extra actions must not enlarge the initial popup", 188f, bounds.height / compose.density.density, 1f)
        }
        assertDefaultViewport()
        compose.onNodeWithTag("reading-status-start-next").assertIsNotDisplayed()
        compose.onNodeWithTag("reading-status-scroll").performTouchInput { swipeUp(durationMillis = 250) }
        compose.onNodeWithTag("reading-status-start-next").assertIsDisplayed().performClick()
        assertEquals(1, starts)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("liquid-glass-morph-card").assertDoesNotExist()
        compose.runOnIdle { active.value = 2; visible.value = true }
        assertDefaultViewport()
        compose.onNodeWithTag("reading-status-abandon").assertIsNotDisplayed()
        compose.onNodeWithTag("reading-status-abandon").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, abandons)
    }
}
