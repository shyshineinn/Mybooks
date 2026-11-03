package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.theme.LiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability
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
        ReadingStatus.READING -> "Reading"
        ReadingStatus.DONE -> "Read"
    }

    private fun runScenario(theme: String, motionEnabled: Boolean) {
        compose.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides theme,
                LocalUseMaterial3Theme provides (theme == "material3"),
                LocalMotionEnabled provides motionEnabled,
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

        val card = compose.onNodeWithTag("reading-status-morph-card")
            .fetchSemanticsNode().boundsInRoot
        val widthDp = card.width / compose.density.density
        assertTrue("selector width was ${widthDp}dp", widthDp in 144f..160f)
        assertEquals(tapOrigin.value.x, card.center.x, 2f)
        assertEquals(tapOrigin.value.y, card.center.y, 2f)

        compose.onNodeWithTag("reading-status-option-reading").performClick()
        compose.waitForIdle()
        assertEquals(ReadingStatus.READING, picked.value)
        assertTrue(saving.value)
        compose.onNodeWithTag("reading-status-option-done").assertIsNotEnabled()

        compose.runOnIdle { close.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("reading-status-morph-card").assertDoesNotExist()
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
    fun `reduced motion still opens and closes the selector at the tap`() =
        runScenario(theme = "lumi", motionEnabled = false)
}
