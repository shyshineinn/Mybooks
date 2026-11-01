package com.huangder.lumibooks.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small line icons that the trimmed Fluent subset does not ship. */
internal val AppCalendarIcon: ImageVector = ImageVector.Builder(
    name = "AppCalendar",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(19f, 4f)
        horizontalLineToRelative(-1f)
        verticalLineTo(2f)
        horizontalLineToRelative(-2f)
        verticalLineToRelative(2f)
        horizontalLineTo(8f)
        verticalLineTo(2f)
        horizontalLineTo(6f)
        verticalLineToRelative(2f)
        horizontalLineTo(5f)
        curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
        verticalLineToRelative(13f)
        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
        horizontalLineToRelative(14f)
        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
        verticalLineTo(6f)
        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
        close()
        moveTo(19f, 19f)
        horizontalLineTo(5f)
        verticalLineTo(9f)
        horizontalLineToRelative(14f)
        verticalLineToRelative(10f)
        close()
    }
}.build()

internal val AppFilterIcon: ImageVector = ImageVector.Builder(
    name = "AppFilter",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 6f)
        horizontalLineTo(21f)
        verticalLineTo(4f)
        horizontalLineTo(3f)
        close()
        moveTo(6f, 13f)
        horizontalLineTo(18f)
        verticalLineTo(11f)
        horizontalLineTo(6f)
        close()
        moveTo(9f, 20f)
        horizontalLineTo(15f)
        verticalLineTo(18f)
        horizontalLineTo(9f)
        close()
    }
}.build()
