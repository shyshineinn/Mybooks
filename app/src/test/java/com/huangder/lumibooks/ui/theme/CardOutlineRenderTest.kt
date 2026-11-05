package com.huangder.lumibooks.ui.theme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.components.InformationCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardOutlineRenderTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var offset by mutableStateOf(0f)
    private var theme by mutableStateOf("lumi")

    @Test fun fractionalDensityAndMovingLayersKeepTheNominalVectorStrokeWidth() {
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(2.75f, 1f), LocalAppTheme provides theme) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
                    Box(Modifier.size(96.dp, 64.dp).graphicsLayer { translationY = offset }
                        .testTag("outline").vectorCardOutline(.5.dp, Color.Black, AppRoundedCornerShape(18.dp)))
                }
            }
        }
        val widths = mutableListOf<Float>()
        for (appearance in listOf("lumi", "material3", "liquid_glass")) {
            for (position in listOf(0f, .25f, .5f, .75f, 7f, 40f, 0f)) {
                compose.runOnIdle { theme = appearance; offset = position }
                val bounds = compose.onNodeWithTag("outline").fetchSemanticsNode().boundsInRoot
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                compose.runOnIdle { view.draw(Canvas(bitmap)) }
                val x = bounds.center.x.toInt()
                val top = floor(bounds.top).toInt() - 1
                var ink = 0f
                for (y in top..top + 6) ink += 1f - android.graphics.Color.red(bitmap.getPixel(x, y)) / 255f
                bitmap.recycle()
                assertEquals("Stroke should retain its .5dp width at fractional density: $appearance $position", 1.375f, ink, .25f)
                widths += ink
            }
        }
        assertTrue("Movement must not double the black edge", widths.max() - widths.min() < .3f)
    }

    @Test fun informationCardEdgeStaysStableOutsideItsMovingShadowAndGlassLayers() {
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(
                LocalDensity provides Density(2.75f, 1f), LocalAppTheme provides theme,
                LocalCardOutlinesEnabled provides true,
                LocalLiquidGlassCapability provides LiquidGlassCapability(true, false)
            ) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
                    InformationCard(Modifier.size(96.dp, 64.dp).graphicsLayer { translationY = offset }.testTag("card")) {
                        Box(Modifier.size(16.dp))
                    }
                }
            }
        }
        for (appearance in listOf("lumi", "material3", "liquid_glass")) {
            val widths = mutableListOf<Float>()
            for (position in listOf(0f, .25f, .5f, .75f, 7f, 40f, 0f)) {
                compose.runOnIdle { theme = appearance; offset = position }
                val bounds = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                compose.runOnIdle { view.draw(Canvas(bitmap)) }
                val x = bounds.center.x.toInt()
                val top = floor(bounds.top).toInt() - 1
                var ink = 0f
                for (y in top..top + 8) ink += 1f - android.graphics.Color.red(bitmap.getPixel(x, y)) / 255f
                bitmap.recycle()
                val nominal = if (appearance == "liquid_glass") 1.375f else 2.75f
                assertTrue("Unexpected card edge thickness: $appearance $position = $ink", ink >= nominal - .25f && ink <= nominal + .6f)
                widths += ink
            }
            assertTrue("Card edge changed during movement: $appearance $widths", widths.max() - widths.min() < .35f)
        }
    }
}
