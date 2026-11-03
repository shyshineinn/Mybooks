package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.toServerStatus
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.components.LiquidGlassMorphPopup
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppType

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

/** Shared measurements for the compact, centered status selector. */
internal object ReadingStatusSelectorMetrics {
    val Width = 132.dp
    val HeaderHeight = 28.dp
    val RowHeight = 48.dp
    val HorizontalPadding = 8.dp
    val VerticalPadding = 8.dp
    val OptionShape = 12.dp
    const val MaxFontScale = 1.8f

    fun scale(fontScale: Float): Float = fontScale.coerceIn(1f, MaxFontScale)
    fun headerHeight(fontScale: Float): Dp = HeaderHeight * scale(fontScale)
    fun rowHeight(fontScale: Float): Dp = RowHeight * scale(fontScale)

    fun contentHeight(fontScale: Float): Dp =
        VerticalPadding * 2 + headerHeight(fontScale) + rowHeight(fontScale) * 3
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
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.server_meta_status),
                fontSize = AppType.Caption,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
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
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Thin status-specific wrapper around the shared morph popup. The popup keeps
 * the page visible; only this component's glass frame moves.
 */
@Composable
internal fun ReadingStatusMorphOverlay(
    anchor: ReadingStatusMorphAnchor,
    selected: ReadingStatus,
    saving: Boolean,
    closeRequested: Boolean,
    onSelect: (ReadingStatus) -> Unit,
    onDismiss: () -> Unit,
    onFinished: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
        val safeBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()
        val marginPx = with(density) { 8.dp.toPx() }
        val rowBounds = anchor.statusRowBounds
        val edgeInsetPx = with(density) { 2.dp.toPx() }
        val inset = minOf(edgeInsetPx, rowBounds.width / 4f)
        val startRect = Rect(
            left = rowBounds.left + inset,
            top = rowBounds.top,
            right = rowBounds.right - inset,
            bottom = rowBounds.bottom
        )
        val targetWidth = minOf(
            ReadingStatusSelectorMetrics.Width,
            with(density) { startRect.width.toDp() }
        )
        val targetHeight = ReadingStatusSelectorMetrics.contentHeight(fontScale)
        val targetRect = readingStatusSelectorTargetRect(
            originCenterX = anchor.originCenter.x,
            originCenterY = anchor.originCenter.y,
            widthPx = with(density) { targetWidth.toPx() },
            heightPx = with(density) { targetHeight.toPx() },
            hostWidthPx = constraints.maxWidth.toFloat(),
            hostHeightPx = constraints.maxHeight.toFloat(),
            safeTopPx = safeTopPx,
            safeBottomPx = safeBottomPx,
            marginPx = marginPx
        )
        val startValue = readingStatusLabel(anchor.status)
        val startLabel = stringResource(R.string.server_meta_status)

        LiquidGlassMorphPopup(
            startRect = startRect,
            targetRect = targetRect,
            closeRequested = closeRequested,
            onDismiss = onDismiss,
            onFinished = onFinished,
            startContentRect = rowBounds,
            startContent = {
                DetailRow(startLabel, startValue, clickable = false)
            },
            content = { enabled ->
                ReadingStatusSelectorContent(
                    selected = selected,
                    saving = saving,
                    enabled = enabled,
                    onSelect = onSelect
                )
            }
        )
    }
}

@Composable
private fun readingStatusLabel(status: ReadingStatus): String = when (status) {
    ReadingStatus.WISH -> stringResource(R.string.reading_status_wish)
    ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
    ReadingStatus.DONE -> stringResource(R.string.reading_status_done)
}
