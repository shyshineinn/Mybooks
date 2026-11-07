package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.toServerStatus
import com.huangder.lumibooks.domain.model.ReadingStatus
import com.huangder.lumibooks.ui.components.AnchoredLiquidGlassMorphPopup
import com.huangder.lumibooks.ui.components.LiquidGlassMorphSelector
import com.huangder.lumibooks.ui.components.LiquidGlassMorphSelectorDefaults

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

/** Domain adapter: shared components own capture, placement, styling and motion. */
@Composable
internal fun ReadingStatusMorphOverlay(
    anchor: ReadingStatusMorphAnchor,
    selected: ReadingStatus,
    saving: Boolean,
    closeRequested: Boolean,
    onSelect: (ReadingStatus) -> Unit,
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
    nextRound: Int? = null,
    activeRound: Int? = null,
    onStartNextReading: () -> Unit = {},
    onAbandonReading: () -> Unit = {}
) {
    val title = stringResource(R.string.server_meta_status)
    val density = LocalDensity.current
    val actionLabel = when {
        activeRound != null -> stringResource(R.string.reading_timeline_abandon, readingRoundLabel(activeRound))
        nextRound != null -> stringResource(R.string.reading_timeline_start_next, readingRoundLabel(nextRound))
        else -> null
    }
    val textMeasurer = rememberTextMeasurer()
    val optionStyle = LocalTextStyle.current.copy(fontSize = AppType.Body, fontWeight = FontWeight.SemiBold)
    val labels = listOf(readingStatusLabel(ReadingStatus.WISH), readingStatusLabel(ReadingStatus.READING), readingStatusLabel(ReadingStatus.DONE)) + listOfNotNull(actionLabel)
    val labelWidth = labels.maxOf { textMeasurer.measure(it, style = optionStyle, softWrap = false).size.width }
    val width = maxOf(LiquidGlassMorphSelectorDefaults.Width,
        with(density) { labelWidth.toDp() } + LiquidGlassMorphSelectorDefaults.HorizontalPadding * 2)
    // Hand the updated value back to the real row before the overlay disappears.
    val returnValue = readingStatusLabel(if (closeRequested) selected else anchor.status)
    AnchoredLiquidGlassMorphPopup(
        anchorBounds = anchor.statusRowBounds,
        originCenter = anchor.originCenter,
        width = width,
        height = LiquidGlassMorphSelectorDefaults.contentHeight(density.fontScale),
        closeRequested = closeRequested,
        onDismiss = onDismiss,
        onFinished = onFinished,
        startContent = { DetailRow(title, returnValue, clickable = false) },
        content = { enabled ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("reading-status-scroll")) {
                LiquidGlassMorphSelector(
                    title = title,
                    options = listOf(ReadingStatus.WISH, ReadingStatus.READING, ReadingStatus.DONE),
                    selected = selected,
                    enabled = enabled && !saving,
                    onSelect = onSelect,
                    optionLabel = { readingStatusLabel(it) },
                    optionModifier = { Modifier.testTag("reading-status-option-${it.toServerStatus()}") }
                )
                if (actionLabel != null) {
                    HorizontalDivider(Modifier.padding(horizontal = LiquidGlassMorphSelectorDefaults.HorizontalPadding), color = AppColors.Divider)
                    TextButton(
                        onClick = if (activeRound != null) onAbandonReading else onStartNextReading, enabled = enabled && !saving,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = LiquidGlassMorphSelectorDefaults.rowHeight(density.fontScale))
                            .testTag(if (activeRound != null) "reading-status-abandon" else "reading-status-start-next")
                    ) {
                        Text(actionLabel, fontSize = AppType.Body, fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center, color = if (saving) AppColors.TextSecondary else AppColors.TextPrimary,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    )
}

@Composable
private fun readingStatusLabel(status: ReadingStatus): String = when (status) {
    ReadingStatus.WISH -> stringResource(R.string.reading_status_wish)
    ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
    ReadingStatus.DONE -> stringResource(R.string.reading_status_done)
}
