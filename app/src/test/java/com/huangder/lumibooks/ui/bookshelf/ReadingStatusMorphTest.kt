package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.ui.geometry.Rect
import com.huangder.lumibooks.domain.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatusMorphTest {
    private val source = Rect(left = 100f, top = 300f, right = 1000f, bottom = 1100f)
    private val width = 660f
    private val height = 560f
    private val hostWidth = 1080f
    private val hostHeight = 2400f
    private val safeTop = 60f
    private val safeBottom = 40f
    private val margin = 24f
    private val statusCenter = 640f
    private val selectedCenterFromTop = 200f

    private fun target(
        card: Rect = source,
        statusOffset: Float = statusCenter,
        hostH: Float = hostHeight,
        selectedCenter: Float = selectedCenterFromTop
    ) = readingStatusSelectorTargetRect(
        source = card,
        statusCenterOffsetPx = statusOffset,
        selectedCenterFromTopPx = selectedCenter,
        widthPx = width,
        heightPx = height,
        hostWidthPx = hostWidth,
        hostHeightPx = hostH,
        safeTopPx = safeTop,
        safeBottomPx = safeBottom,
        marginPx = margin
    )

    @Test
    fun `right edge stays aligned with the information card`() {
        val rect = target()

        assertEquals(source.right, rect.right, 0.001f)
        assertEquals(width, rect.width, 0.001f)
        assertEquals(source.right - width, rect.left, 0.001f)
    }

    @Test
    fun `selected option center lines up with the original status row`() {
        val rect = target()

        assertEquals(source.top + statusCenter, rect.top + selectedCenterFromTop, 0.001f)
    }

    @Test
    fun `card shifts down when the status row is too close to the top`() {
        val rect = target(statusOffset = 0f, selectedCenter = 400f)

        assertEquals(safeTop + margin, rect.top, 0.001f)
        assertEquals(source.right, rect.right, 0.001f)
    }

    @Test
    fun `card shifts up when the status row is too close to the bottom`() {
        val shortHost = 1200f
        val rect = target(statusOffset = 800f, hostH = shortHost)

        assertEquals(shortHost - safeBottom - margin - height, rect.top, 0.001f)
        assertTrue(rect.bottom <= shortHost - safeBottom - margin)
    }

    @Test
    fun `right edge is clamped only when the card itself leaves the screen`() {
        val offscreen = Rect(left = 900f, top = 300f, right = 1200f, bottom = 1100f)
        val rect = target(card = offscreen)

        assertEquals(hostWidth - margin, rect.right, 0.001f)
    }

    @Test
    fun `three states map to stable option indexes`() {
        assertEquals(0, ReadingStatus.WISH.selectorIndex())
        assertEquals(1, ReadingStatus.READING.selectorIndex())
        assertEquals(2, ReadingStatus.DONE.selectorIndex())
    }
}
