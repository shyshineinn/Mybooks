package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.toServerStatus
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.components.LiquidGlassMenuMotion
import com.huangder.lumibooks.ui.components.MenuContentPadding
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppType

/** Live layout bounds for the card/status row; intentionally not Compose state. */
internal class ReadingStatusCardGeometry {
    var cardBounds: Rect = Rect.Zero
    var statusRowBounds: Rect = Rect.Zero
}

/** Details-page source rectangle captured before the card starts morphing. */
internal data class ReadingStatusMorphAnchor(
    val sourceId: Any,
    val cardBounds: Rect,
    val statusCenterOffsetPx: Float,
    val status: ReadingStatus
)

/**
 * Visual measurements shared by the selector content and the geometry target.
 * Row heights scale with the system font so taps and labels stay readable.
 */
internal object ReadingStatusSelectorMetrics {
    val Width = 220.dp
    val HeaderHeight = 28.dp
    val RowHeight = 48.dp
    val HorizontalPadding = 12.dp
    val OptionShape = 12.dp
    const val MaxFontScale = 1.8f

    fun scale(fontScale: Float): Float = fontScale.coerceIn(1f, MaxFontScale)

    fun headerHeight(fontScale: Float): Dp = HeaderHeight * scale(fontScale)

    fun rowHeight(fontScale: Float): Dp = RowHeight * scale(fontScale)

    /** Distance from the settled card top to the selected row's center. */
    fun selectedCenterFromTop(fontScale: Float, selectedIndex: Int): Dp {
        val index = selectedIndex.coerceIn(0, 2)
        return MenuContentPadding + headerHeight(fontScale) + rowHeight(fontScale) * (index + 0.5f)
    }
}

/**
 * The target keeps the card's right edge fixed and moves the opposite edge
 * toward the original status row. Near a screen edge only the vertical axis
 * shifts; horizontal alignment is never sacrificed.
 */
internal fun readingStatusSelectorTargetRect(
    source: Rect,
    statusCenterOffsetPx: Float,
    selectedCenterFromTopPx: Float,
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
    val minRight = marginPx + width
    val maxRight = (hostWidthPx - marginPx).coerceAtLeast(minRight)
    val right = source.right.coerceIn(minRight, maxRight)
    val left = right - width
    val cardHeight = heightPx.coerceAtLeast(1f)

    val desiredTop = source.top + statusCenterOffsetPx - selectedCenterFromTopPx
    val minTop = safeTopPx + marginPx
    val maxTop = (hostHeightPx - safeBottomPx - marginPx - cardHeight).coerceAtLeast(minTop)
    val top = desiredTop.coerceIn(minTop, maxTop)
    return Rect(left, top, right, top + cardHeight)
}

/** Same 320ms-scale spring as other anchored glass, sped up by the motion setting. */
internal val ReadingStatusSelectorMotion = LiquidGlassMenuMotion(
    openStiffness = 520f,
    openDampingRatio = 0.82f,
    closeStiffness = 700f,
    closeDampingRatio = 0.84f
)

internal fun ReadingStatus.selectorIndex(): Int = when (this) {
    ReadingStatus.WISH -> 0
    ReadingStatus.READING -> 1
    ReadingStatus.DONE -> 2
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
    Column(modifier.fillMaxWidth().selectableGroup()) {
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

@Composable
private fun readingStatusLabel(status: ReadingStatus): String = when (status) {
    ReadingStatus.WISH -> stringResource(R.string.reading_status_wish)
    ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
    ReadingStatus.DONE -> stringResource(R.string.reading_status_done)
}
