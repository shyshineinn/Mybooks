package com.huangder.lumibooks.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.huangder.lumibooks.ui.theme.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiquidGlassDialogHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticDialogCaptureDoesNotLeakIntoPageControlsOrDrawBlackCardOutlines() {
        var pageSource: Backdrop? = null
        var popupSource: Backdrop? = null
        var pageOutline = false
        var popupOutline = true
        val supported = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalAppTheme provides "liquid_glass",
                LocalMotionEnabled provides false, LocalCardOutlinesEnabled provides true,
                LocalLiquidGlassCapability provides LiquidGlassCapability(supported.value, false)) {
                LiquidGlassDialogHost(Modifier.fillMaxSize()) {
                    pageSource = LocalLiquidGlassBackdrop.current
                    pageOutline = LocalCardOutlinesEnabled.current
                    Box(Modifier.fillMaxSize().background(Color.Cyan))
                    LiquidGlassDialog(onDismissRequest = {}, contentScrimColor = Color.White.copy(alpha = .24f)) {
                        popupSource = LocalLiquidGlassBackdrop.current
                        popupOutline = LocalCardOutlinesEnabled.current
                        Box(Modifier.size(120.dp))
                    }
                }
            }
        }
        for (available in listOf(true, false)) {
            compose.runOnIdle { supported.value = available }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNull("The page cannot sample its own private dialog capture", pageSource)
                assertNotNull("The popup must receive a real backdrop source", popupSource)
                assertTrue("The page outline preference is unchanged", pageOutline)
                assertFalse("Popup fallback must never add the optional black card border", popupOutline)
            }
        }
    }

    @Test fun anExplicitDialogBackdropIsStillUsed() {
        var supplied: Backdrop? = null
        var popupSource: Backdrop? = null
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            supplied = backdrop
            CompositionLocalProvider(LocalAppTheme provides "liquid_glass", LocalMotionEnabled provides false,
                LocalLiquidGlassCapability provides LiquidGlassCapability(true, false)) {
                LiquidGlassDialogHost(Modifier.fillMaxSize(), backdrop) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.Cyan))
                    LiquidGlassDialog(onDismissRequest = {}) {
                        popupSource = LocalLiquidGlassBackdrop.current
                        Box(Modifier.size(120.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull(supplied); assertSame(supplied, popupSource) }
    }
}
