package com.huangder.lumibooks.ui.bookshelf

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.toServerStatus
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.animation.VisualMotion
import com.huangder.lumibooks.ui.components.LiquidGlassSurface
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import kotlin.math.roundToInt

/** The live status-row bounds and the tap origin captured when the selector opens. */
internal data class ReadingStatusMorphAnchor(
    val statusRowBounds: Rect,
    val originCenter: Offset,
    val status: ReadingStatus
)

/** Kept outside Compose state so scrolling does not recompose the information card. */
internal class ReadingStatusCardGeometry {
    var statusRowBounds: Rect = Rect.Zero
}

/**
 * Shared measurements for the compact selector. Width starts at 152dp, stays
 * between 144–160dp for normal text, and grows up to 200dp for large font scales.
 */
internal object ReadingStatusSelectorMetrics {
    val PreferredWidth = 152.dp
    val MinWidth = 144.dp
    val MaxWidth = 160.dp
    val MaxWidthLargeText = 200.dp
    val HeaderHeight = 28.dp
    val RowHeight = 48.dp
    val HorizontalPadding = 12.dp
    val VerticalPadding = 8.dp
    val OptionShape = 12.dp
    const val LargeTextThreshold = 1.3f
    const val MaxFontScale = 1.8f

    fun scale(fontScale: Float): Float = fontScale.coerceIn(1f, MaxFontScale)
    fun headerHeight(fontScale: Float): Dp = HeaderHeight * scale(fontScale)
    fun rowHeight(fontScale: Float): Dp = RowHeight * scale(fontScale)

    fun contentHeight(fontScale: Float): Dp =
        VerticalPadding * 2 + headerHeight(fontScale) + rowHeight(fontScale) * 3

    fun widthForText(textWidth: Dp, fontScale: Float): Dp {
        val max = if (scale(fontScale) >= LargeTextThreshold) MaxWidthLargeText else MaxWidth
        return maxOf(
            PreferredWidth,
            minOf(textWidth + HorizontalPadding * 2 + 4.dp, max)
        )
    }
}

/** The selector settles centered on the tap, with only edge-space correction. */
internal fun readingStatusSelectorTargetRect(
    originCenterX: Float,
    originCenterY: Float,
    widthPx: Float,
    heightPx: Float,
    hostWidthPx: Float,
    hostHeightPx: Float,
    safeTopPx: Float,
    safeBottomPx: Float,
    marginPx: Float
): Rect {
    val maxWidth = (hostWidthPx - marginPx * 2f).coerceAtLeast(1f)
    val width = widthPx.coerceIn(1f, maxWidth)
    val maxLeft = (hostWidthPx - marginPx - width).coerceAtLeast(marginPx)
    val left = (originCenterX - width / 2f).coerceIn(marginPx, maxLeft)
    val cardHeight = heightPx.coerceAtLeast(1f)
    val minTop = safeTopPx + marginPx
    val maxTop = (hostHeightPx - safeBottomPx - marginPx - cardHeight).coerceAtLeast(minTop)
    val top = (originCenterY - cardHeight / 2f).coerceIn(minTop, maxTop)
    return Rect(left, top, left + width, top + cardHeight)
}

@Composable
internal fun ReadingStatusSelectorContent(
    selected: ReadingStatus,
    saving: Boolean,
    enabled: Boolean,
    onSelect: (ReadingStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val fontScale = ReadingStatusSelectorMetrics.scale(LocalDensity.current.fontScale)
    val options = listOf(ReadingStatus.WISH, ReadingStatus.READING, ReadingStatus.DONE)
    Column(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(vertical = ReadingStatusSelectorMetrics.VerticalPadding)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(ReadingStatusSelectorMetrics.headerHeight(fontScale))
                .padding(horizontal = ReadingStatusSelectorMetrics.HorizontalPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = stringResource(R.string.server_meta_status),
                fontSize = AppType.Caption,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ReadingStatusSelectorMetrics.rowHeight(fontScale))
                    .clip(AppRoundedCornerShape(ReadingStatusSelectorMetrics.OptionShape))
                    .testTag("reading-status-option-${option.toServerStatus()}")
                    .selectable(
                        selected = isSelected,
                        enabled = enabled && !saving,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .padding(horizontal = ReadingStatusSelectorMetrics.HorizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = readingStatusLabel(option),
                    fontSize = AppType.Body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) AppColors.Accent else AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * A small standalone morph layer over the page. The information card stays in
 * place; only its status row is hidden while this layer owns that row's glass.
 */
@Composable
internal fun ReadingStatusMorphOverlay(
    anchor: ReadingStatusMorphAnchor,
    selected: ReadingStatus,
    saving: Boolean,
    closeRequested: Boolean,
    onSelect: (ReadingStatus) -> Unit,
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val motionEnabled = LocalMotionEnabled.current
        // Start as the full status-row frame: wide and short. The geometry then
        // narrows horizontally while growing vertically into the selector.
        val startRect = anchor.statusRowBounds
        val hostWidthPx = constraints.maxWidth.toFloat()
        val hostHeightPx = constraints.maxHeight.toFloat()
        val safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
        val safeBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()
        val marginPx = with(density) { 8.dp.toPx() }

        val headerText = stringResource(R.string.server_meta_status)
        val optionLabels = listOf(
            stringResource(R.string.reading_status_wish),
            stringResource(R.string.reading_status_reading),
            stringResource(R.string.reading_status_done)
        )
        val textMeasurer = rememberTextMeasurer()
        val textWidthPx = remember(textMeasurer, fontScale, headerText, optionLabels) {
            maxOf(
                textMeasurer.measure(
                    headerText,
                    style = TextStyle(fontSize = AppType.Caption, fontWeight = FontWeight.SemiBold),
                    maxLines = 1
                ).size.width,
                optionLabels.maxOf {
                    textMeasurer.measure(
                        it,
                        style = TextStyle(fontSize = AppType.Body, fontWeight = FontWeight.SemiBold),
                        maxLines = 1
                    ).size.width
                }
            )
        }
        val availableWidth = with(density) { startRect.width.toDp() }
        val targetWidth = minOf(
            ReadingStatusSelectorMetrics.widthForText(
                with(density) { textWidthPx.toDp() },
                fontScale
            ),
            availableWidth
        )
        val targetHeight = ReadingStatusSelectorMetrics.contentHeight(fontScale)
        val targetRect = remember(
            anchor.originCenter,
            targetWidth,
            targetHeight,
            hostWidthPx,
            hostHeightPx,
            safeTopPx,
            safeBottomPx,
            marginPx
        ) {
            readingStatusSelectorTargetRect(
                originCenterX = anchor.originCenter.x,
                originCenterY = anchor.originCenter.y,
                widthPx = with(density) { targetWidth.toPx() },
                heightPx = with(density) { targetHeight.toPx() },
                hostWidthPx = hostWidthPx,
                hostHeightPx = hostHeightPx,
                safeTopPx = safeTopPx,
                safeBottomPx = safeBottomPx,
                marginPx = marginPx
            )
        }

        val progress = remember { Animatable(0f) }
        var settled by remember { mutableStateOf(false) }
        val currentOnFinished by rememberUpdatedState(onFinished)

        LaunchedEffect(anchor) {
            settled = false
            if (motionEnabled) {
                progress.animateTo(
                    1f,
                    VisualMotion.spring(
                        dampingRatio = 0.9f,
                        stiffness = 300f,
                        visibilityThreshold = 0.001f
                    )
                )
            } else {
                progress.snapTo(1f)
            }
            settled = true
        }
        LaunchedEffect(closeRequested) {
            if (!closeRequested) return@LaunchedEffect
            settled = false
            if (motionEnabled) {
                progress.animateTo(
                    0f,
                    VisualMotion.spring(
                        dampingRatio = 0.9f,
                        stiffness = 360f,
                        visibilityThreshold = 0.001f
                    )
                )
            } else {
                progress.snapTo(0f)
            }
            currentOnFinished()
        }

        val rectState = remember(startRect, targetRect) {
            derivedStateOf { lerpRect(startRect, targetRect, progress.value) }
        }
        val rowAlpha = { rowContentAlpha(progress.value) }
        val selectorAlpha = { selectorContentAlpha(progress.value) }
        val startRadiusPx = with(density) { 8.dp.toPx() }
        val targetRadiusPx = with(density) { AppRadius.xl.toPx() }
        val shapeRadius = with(density) {
            lerp(startRadiusPx, targetRadiusPx, progress.value).toDp()
        }
        val shape = AppRoundedCornerShape(shapeRadius)
        val startValue = readingStatusLabel(anchor.status)
        val startLabel = stringResource(R.string.server_meta_status)
        val targetWidthDp = with(density) { targetRect.width.toDp() }
        val targetHeightDp = with(density) { targetRect.height.toDp() }
        val startWidthDp = with(density) { startRect.width.toDp() }
        val startHeightDp = with(density) { startRect.height.toDp() }

        BackHandler {
            if (!closeRequested) onDismiss()
        }

        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { dismissGestures(onDismiss) })

        Box(
            Modifier
                .absoluteOffset {
                    IntOffset(rectState.value.left.roundToInt(), rectState.value.top.roundToInt())
                }
                .layout { measurable, constraints ->
                    val bounds = rectState.value
                    val width = constraints.constrainWidth(bounds.width.roundToInt().coerceAtLeast(1))
                    val height = constraints.constrainHeight(bounds.height.roundToInt().coerceAtLeast(1))
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }
                .testTag("reading-status-morph-card")
        ) {
            LiquidGlassSurface(
                shape = shape,
                fallbackColor = AppColors.CardBg,
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.TopStart
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.shape = shape
                            clip = true
                        }
                ) {
                    Box(
                        Modifier
                            .absoluteOffset {
                                val rect = rectState.value
                                IntOffset(
                                    (startRect.left - rect.left).roundToInt(),
                                    (startRect.top - rect.top).roundToInt()
                                )
                            }
                            .size(startWidthDp, startHeightDp)
                            .graphicsLayer { alpha = rowAlpha() }
                    ) {
                        DetailRow(startLabel, startValue, clickable = false)
                    }
                    Box(
                        Modifier
                            .absoluteOffset {
                                val rect = rectState.value
                                IntOffset(
                                    (targetRect.left - rect.left).roundToInt(),
                                    (targetRect.top - rect.top).roundToInt()
                                )
                            }
                            .size(targetWidthDp, targetHeightDp)
                            .graphicsLayer { alpha = selectorAlpha() }
                            .then(
                                if (!settled && !closeRequested) {
                                    Modifier.clearAndSetSemantics {}
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        ReadingStatusSelectorContent(
                            selected = selected,
                            saving = saving,
                            enabled = settled && !closeRequested,
                            onSelect = onSelect
                        )
                    }
                }
            }
        }
    }
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect = Rect(
    left = lerp(start.left, end.left, fraction),
    top = lerp(start.top, end.top, fraction),
    right = lerp(start.right, end.right, fraction),
    bottom = lerp(start.bottom, end.bottom, fraction)
)

private fun rowContentAlpha(progress: Float): Float {
    val t = ((progress - 0.05f) / 0.3f).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

private fun selectorContentAlpha(progress: Float): Float {
    val t = ((progress - 0.4f) / 0.32f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dismissGestures(
    onDismiss: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.positionChange().getDistance() > viewConfiguration.touchSlop) moved = true
            change.consume()
            if (!change.pressed) break
        }
        if (!moved) onDismiss()
    }
}

@Composable
private fun readingStatusLabel(status: ReadingStatus): String = when (status) {
    ReadingStatus.WISH -> stringResource(R.string.reading_status_wish)
    ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
    ReadingStatus.DONE -> stringResource(R.string.reading_status_done)
}
