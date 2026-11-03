package com.huangder.lumibooks.ui.bookshelf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.domain.model.MenuAnimationStyle
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.components.LiquidGlassMenuHost
import com.huangder.lumibooks.ui.components.LiquidGlassMenuHostState
import com.huangder.lumibooks.ui.components.LiquidGlassMenuSpec
import com.huangder.lumibooks.ui.components.LocalLiquidGlassMenuHost
import com.huangder.lumibooks.ui.components.liquidGlassMenuAnchor
import com.huangder.lumibooks.ui.theme.LiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability
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
class ReadingStatusCardMorphHostTest {
    @get:Rule val compose = createComposeRule()
    private val sourceId = "reading-status-card"
    private val lookupSpec = LiquidGlassMenuSpec(
        anchorBounds = Rect.Zero,
        width = ReadingStatusSelectorMetrics.Width,
        items = emptyList(),
        sourceId = sourceId
    )
    private lateinit var host: LiquidGlassMenuHostState
    private lateinit var view: View
    private var picked: ReadingStatus? = null

    private fun advance(milliseconds: Long) {
        compose.waitForIdle()
        var remaining = milliseconds
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            compose.runOnIdle { render().recycle() }
            remaining -= step
        }
    }

    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun sourceLeftTopPx(): Pair<Int, Int> {
        val bounds = host.source(lookupSpec)!!.rootBounds
        return bounds.left.toInt() + 10 to bounds.top.toInt() + 10
    }

    private fun showSelector() {
        val density = compose.density
        val statusOffsetPx = with(density) { 120.dp.toPx() }
        val selectedCenter = with(density) {
            ReadingStatusSelectorMetrics.selectedCenterFromTop(
                density.fontScale,
                ReadingStatus.WISH.selectorIndex()
            ).toPx()
        }
        val margin = with(density) { 8.dp.toPx() }
        compose.runOnIdle {
            host.show(
                LiquidGlassMenuSpec(
                    anchorBounds = Rect.Zero,
                    width = ReadingStatusSelectorMetrics.Width,
                    items = emptyList(),
                    sourceId = sourceId,
                    anchorCornerRadius = 18.dp,
                    motion = ReadingStatusSelectorMotion,
                    keepTriggerInteractive = false,
                    cardMorph = true,
                    cardSurface = true,
                    targetBounds = { source, measuredHeight, widthPx, hostWidth, hostHeight, safeTop, safeBottom ->
                        readingStatusSelectorTargetRect(
                            source = source,
                            statusCenterOffsetPx = statusOffsetPx,
                            selectedCenterFromTopPx = selectedCenter,
                            widthPx = widthPx,
                            heightPx = measuredHeight,
                            hostWidthPx = hostWidth,
                            hostHeightPx = hostHeight,
                            safeTopPx = safeTop,
                            safeBottomPx = safeBottom,
                            marginPx = margin
                        )
                    },
                    content = { enabled, _ ->
                        ReadingStatusSelectorContent(
                            selected = ReadingStatus.WISH,
                            saving = false,
                            enabled = enabled,
                            onSelect = {
                                picked = it
                                host.dismiss()
                            }
                        )
                    }
                )
            )
        }
    }

    private fun runMorph(glass: Boolean, motionEnabled: Boolean) {
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(
                LocalMotionEnabled provides motionEnabled,
                // The card morph is its own motion; the normal menu style must not
                // silently replace it with a fade or leave the old card visible.
                LocalMenuAnimationStyle provides MenuAnimationStyle.NORMAL,
                LocalAppTheme provides if (glass) "liquid_glass" else "lumi",
                LocalLiquidGlassCapability provides if (glass) {
                    LiquidGlassCapability(true, false)
                } else {
                    LocalLiquidGlassCapability.current
                }
            ) {
                LiquidGlassMenuHost(Modifier.fillMaxSize()) {
                    host = LocalLiquidGlassMenuHost.current!!
                    Box(Modifier.fillMaxSize().background(Color.LightGray)) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 80.dp, end = 16.dp)
                                .liquidGlassMenuAnchor(
                                    id = sourceId,
                                    cornerRadius = 18.dp,
                                    recordOnce = true,
                                    recordRevision = "card-1"
                                )
                                .testTag("reading-status-card")
                                .size(300.dp, 240.dp)
                                .background(Color.White)
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            val (x, y) = sourceLeftTopPx()
            val bitmap = render()
            try {
                assertEquals(Color.White.toArgb(), bitmap.getPixel(x, y))
            } finally {
                bitmap.recycle()
            }
            assertTrue(host.source(lookupSpec)!!.recorded)
        }
        compose.mainClock.autoAdvance = false

        showSelector()
        advance(1000)
        compose.onNodeWithTag("reading-status-option-wish").assertExists()
        compose.onNodeWithTag("reading-status-option-reading").assertExists()
        compose.onNodeWithTag("reading-status-card").assertDoesNotExist()
        compose.runOnIdle {
            val (x, y) = sourceLeftTopPx()
            val bitmap = render()
            try {
                assertEquals(Color.LightGray.toArgb(), bitmap.getPixel(x, y))
            } finally {
                bitmap.recycle()
            }
        }

        compose.onNodeWithTag("reading-status-option-reading").performClick()
        advance(1200)
        compose.runOnIdle { assertEquals(ReadingStatus.READING, picked) }
        compose.onNodeWithTag("reading-status-card").assertExists()
        compose.runOnIdle {
            val (x, y) = sourceLeftTopPx()
            val bitmap = render()
            try {
                assertEquals(Color.White.toArgb(), bitmap.getPixel(x, y))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `whole card morph works in the lumi theme`() =
        runMorph(glass = false, motionEnabled = true)

    @Test
    fun `whole card morph works in the liquid glass theme`() =
        runMorph(glass = true, motionEnabled = true)

    @Test
    fun `reduced motion still hands the whole card off without double glass`() =
        runMorph(glass = false, motionEnabled = false)
}
