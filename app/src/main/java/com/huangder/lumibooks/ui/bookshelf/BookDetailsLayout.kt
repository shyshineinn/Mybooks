package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class BookDetailsLayoutSpec(
    val twoPane: Boolean,
    val showHero: Boolean,
    val compactHero: Boolean,
    val sidebarWidthDp: Float
)

/** Use usable window space rather than the device's orientation or physical display size. */
internal fun bookDetailsLayoutSpec(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float
): BookDetailsLayoutSpec {
    val scale = fontScale.coerceAtLeast(1f)
    val sidebar = (widthDp * 0.28f).coerceIn(160f, 240f)
    // The action bar also needs at least 360dp; larger labels need more reading width.
    val minimumContentWidth = maxOf(360f, 260f * scale)
    val twoPane = widthDp > heightDp && widthDp - sidebar - 16f >= minimumContentWidth
    return BookDetailsLayoutSpec(
        twoPane = twoPane,
        showHero = twoPane || heightDp >= 420f * scale.coerceAtMost(1.4f),
        compactHero = heightDp < 480f || scale >= 1.5f,
        sidebarWidthDp = sidebar
    )
}

/**
 * The four slots keep the same composition identity across window changes. In particular,
 * the pager is never moved into a different Row/Column branch, so its scroll and saved page
 * state survive rotation. Actions overlay the full-height content viewport; their measured
 * height is passed to the list for trailing scroll clearance, never viewport padding.
 */
@Composable
internal fun BookDetailsLayout(
    modifier: Modifier = Modifier,
    topBar: @Composable (showHero: Boolean) -> Unit,
    hero: @Composable (twoPane: Boolean, compact: Boolean) -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable (bottomOverlayHeight: Dp) -> Unit
) {
    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val density = LocalDensity.current
        val spec = bookDetailsLayoutSpec(maxWidth.value, maxHeight.value, density.fontScale)
        // Safe initial clearance until the first measurement (64dp bar + 16dp outer gap).
        var actionsHeight by remember(density) { mutableStateOf(80.dp) }
        Layout(
            modifier = Modifier.fillMaxSize()
                .testTag(if (spec.twoPane) "book_details_two_pane" else "book_details_single_pane"),
            content = {
                Box(Modifier.fillMaxWidth().testTag("book_details_top_bar")) { topBar(spec.showHero) }
                Box(
                    Modifier.clipToBounds().testTag("book_details_hero")
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (spec.showHero) hero(spec.twoPane, spec.compactHero)
                }
                Box(Modifier.testTag("book_details_content")) { content(actionsHeight) }
                Box(
                    Modifier.fillMaxWidth().testTag("book_details_actions")
                        .onSizeChanged { actionsHeight = with(density) { it.height.toDp() } }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { actions() }
            }
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val topBar = measurables[0].measure(Constraints.fixedWidth(width).copy(maxHeight = height))
            val bodyHeight = (height - topBar.height).coerceAtLeast(0)
            val sidebarWidth = if (spec.twoPane) spec.sidebarWidthDp.dp.roundToPx() else 0
            val gap = if (spec.twoPane) 16.dp.roundToPx() else 0
            val contentWidth = (width - sidebarWidth - gap).coerceAtLeast(0)
            val footer = measurables[3].measure(
                Constraints.fixedWidth(contentWidth).copy(maxHeight = bodyHeight)
            )
            val heroMaxHeight = when {
                spec.twoPane -> bodyHeight
                spec.showHero -> minOf(bodyHeight, 168.dp.roundToPx())
                else -> 0
            }
            val hero = measurables[1].measure(
                Constraints.fixedWidth(if (spec.twoPane) sidebarWidth else width)
                    .copy(maxHeight = heroMaxHeight)
            )
            val contentTop = topBar.height + if (spec.twoPane) 0 else hero.height
            val contentHeight = (height - contentTop).coerceAtLeast(0)
            val page = measurables[2].measure(Constraints.fixed(contentWidth, contentHeight))

            layout(width, height) {
                topBar.placeRelative(0, 0)
                hero.placeRelative(0, topBar.height)
                page.placeRelative(sidebarWidth + gap, contentTop)
                footer.placeRelative(sidebarWidth + gap, height - footer.height)
            }
        }
    }
}
