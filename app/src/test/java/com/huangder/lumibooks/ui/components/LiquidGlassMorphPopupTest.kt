package com.huangder.lumibooks.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import com.huangder.lumibooks.domain.model.MenuAnimationStyle
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalMenuAnimationStyle
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
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
class LiquidGlassMorphPopupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `text layers retain their root positions throughout opening and return`() {
        val close = mutableStateOf(false)
        var finished = false
        var startBounds = Rect.Zero
        var targetBounds = Rect.Zero
        val start = Rect(40f, 320f, 600f, 368f)
        val target = Rect(269f, 250f, 371f, 438f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalAppTheme provides "lumi",
                LocalMenuAnimationStyle provides MenuAnimationStyle.LIQUID,
                LocalMotionEnabled provides true
            ) {
                Box(Modifier.fillMaxSize()) {
                    LiquidGlassMorphPopup(
                        startRect = start,
                        startContentRect = start,
                        targetRect = target,
                        closeRequested = close.value,
                        onDismiss = { close.value = true },
                        onFinished = { finished = true },
                        startContent = {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { startBounds = Rect(it.positionInRoot(), it.size.toSize()) }) {
                                Text("Status: Reading")
                            }
                        },
                        content = {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { targetBounds = Rect(it.positionInRoot(), it.size.toSize()) }) {
                                Text("Reading")
                            }
                        }
                    )
                }
            }
        }
        fun assertLayersStayAnchored() {
            compose.waitForIdle()
            // The glass frame changes size, but neither fixed-size text layer may
            // inherit the centering offset from requiredSize's constraint coercion.
            assertEquals(start.left, startBounds.left, 1f)
            assertEquals(start.top, startBounds.top, 1f)
            assertEquals(start.width, startBounds.width, 1f)
            assertEquals(target.left, targetBounds.left, 1f)
            assertEquals(target.top, targetBounds.top, 1f)
            assertEquals(target.width, targetBounds.width, 1f)
        }
        repeat(8) {
            compose.mainClock.advanceTimeBy(48)
            assertLayersStayAnchored()
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { close.value = true }
        repeat(8) {
            compose.mainClock.advanceTimeBy(48)
            assertLayersStayAnchored()
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertTrue(finished) }
    }
}
