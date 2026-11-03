package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatusMorphTest {
    private val hostWidth = 1080f
    private val hostHeight = 2400f
    private val safeTop = 60f
    private val safeBottom = 40f
    private val margin = 24f
    private val width = 456f
    private val height = 564f

    private fun target(
        centerX: Float = 540f,
        centerY: Float = 1200f,
        hostW: Float = hostWidth,
        hostH: Float = hostHeight
    ) = readingStatusSelectorTargetRect(
        originCenterX = centerX,
        originCenterY = centerY,
        widthPx = width,
        heightPx = height,
        hostWidthPx = hostW,
        hostHeightPx = hostH,
        safeTopPx = safeTop,
        safeBottomPx = safeBottom,
        marginPx = margin
    )

    @Test
    fun `selector settles centered on the tap position`() {
        val rect = target()

        assertEquals(540f, rect.center.x, 0.001f)
        assertEquals(1200f, rect.center.y, 0.001f)
        assertEquals(width, rect.width, 0.001f)
        assertEquals(height, rect.height, 0.001f)
    }

    @Test
    fun `left edge is corrected only when the tap is near a horizontal edge`() {
        val leftRect = target(centerX = 0f)
        val rightRect = target(centerX = hostWidth)

        assertEquals(margin, leftRect.left, 0.001f)
        assertEquals(hostWidth - margin, rightRect.right, 0.001f)
    }

    @Test
    fun `vertical position is corrected only when the tap is near a vertical edge`() {
        val topRect = target(centerY = 0f)
        val bottomRect = target(centerY = hostHeight)

        assertEquals(safeTop + margin, topRect.top, 0.001f)
        assertEquals(hostHeight - safeBottom - margin, bottomRect.bottom, 0.001f)
    }

    @Test
    fun `width never exceeds the available screen width`() {
        val rect = target(hostW = 320f)

        assertTrue(rect.width <= 320f - margin * 2f + 0.001f)
        assertEquals(320f - margin * 2f, rect.width, 0.001f)
    }

    @Test
    fun `selector width starts compact and grows for large text`() {
        assertEquals(152.dp, ReadingStatusSelectorMetrics.widthForText(80.dp, 1f))
        assertEquals(160.dp, ReadingStatusSelectorMetrics.widthForText(200.dp, 1f))
        assertEquals(200.dp, ReadingStatusSelectorMetrics.widthForText(200.dp, 1.5f))
        assertEquals(188.dp, ReadingStatusSelectorMetrics.contentHeight(1f))
    }
}
