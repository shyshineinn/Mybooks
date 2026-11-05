package com.huangder.lumibooks.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.translate

/** Shares the exact clipping outline with Canvas borders, charts and crop masks. */
internal fun Outline.toAppPath(): Path = when (this) {
    is Outline.Generic -> Path().also { it.addPath(path) }
    is Outline.Rounded -> Path().also { it.addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().also { it.addRect(rect) }
}

internal fun DrawScope.drawAppShape(
    shape: Shape,
    color: Color,
    topLeft: Offset = Offset.Zero,
    size: Size = this.size,
    style: DrawStyle = Fill
) {
    if (size.width <= 0f || size.height <= 0f) return
    val outline = shape.createOutline(size, layoutDirection, this)
    translate(topLeft.x, topLeft.y) { drawOutline(outline, color, style = style) }
}

internal fun DrawScope.drawAppShape(
    shape: Shape,
    brush: Brush,
    topLeft: Offset = Offset.Zero,
    size: Size = this.size,
    style: DrawStyle = Fill
) {
    if (size.width <= 0f || size.height <= 0f) return
    val outline = shape.createOutline(size, layoutDirection, this)
    translate(topLeft.x, topLeft.y) { drawOutline(outline, brush, style = style) }
}

/** Draw the optional edge as vector geometry, without a cached bitmap mask or ceil rounding. */
internal fun Modifier.vectorCardOutline(width: Dp, color: Color, shape: Shape): Modifier = drawWithCache {
    val path = shape.createOutline(size, layoutDirection, this).toAppPath()
    val strokeWidth = width.toPx().coerceAtLeast(1f).coerceAtMost(size.minDimension / 2f)
    onDrawWithContent {
        drawContent()
        if (strokeWidth > 0f && color.alpha > 0f) {
            // A doubled, inside-clipped stroke keeps the original card bounds and corners.
            clipPath(path) { drawPath(path, color, style = Stroke(strokeWidth * 2f)) }
        }
    }
}
