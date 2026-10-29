package com.huangder.lumibooks.ui.bookshelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailsLayoutTest {
    @Test
    fun landscapePhoneHasTwoPanesAndBoundedSidebar() {
        val layout = bookDetailsLayoutSpec(800f, 360f, 1f)
        assertTrue(layout.twoPane)
        assertTrue(layout.showHero)
        assertTrue(layout.sidebarWidthDp in 160f..240f)
    }

    @Test
    fun narrowWindowAndLargeFontFallBackToCompactSinglePane() {
        val narrow = bookDetailsLayoutSpec(500f, 320f, 1f)
        assertFalse(narrow.twoPane)
        assertFalse(narrow.showHero)
        assertTrue(narrow.compactHero)
        assertFalse(bookDetailsLayoutSpec(700f, 360f, 2f).twoPane)
    }

    @Test
    fun portraitKeepsHeroAndLargeLandscapeKeepsReadingWidth() {
        assertFalse(bookDetailsLayoutSpec(400f, 800f, 1f).twoPane)
        assertTrue(bookDetailsLayoutSpec(400f, 800f, 1f).showHero)
        assertTrue(bookDetailsLayoutSpec(1100f, 650f, 2f).twoPane)
    }

    @Test
    fun shortFieldStaysHorizontal() {
        assertFalse(shouldStackDetailRow(80f, 120f, 320f, 16f))
    }

    @Test
    fun longTitleOrUnbrokenFileNameStacks() {
        assertTrue(shouldStackDetailRow(80f, 360f, 320f, 16f))
        assertTrue(shouldStackDetailRow(160f, 160f, 320f, 16f))
    }
}
