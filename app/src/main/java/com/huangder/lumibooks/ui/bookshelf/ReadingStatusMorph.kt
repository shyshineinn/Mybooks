package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    onFinished: () -> Unit
) {
    val title = stringResource(R.string.server_meta_status)
    // Hand the updated value back to the real row before the overlay disappears.
    val returnValue = readingStatusLabel(if (closeRequested) selected else anchor.status)
    AnchoredLiquidGlassMorphPopup(
        anchorBounds = anchor.statusRowBounds,
        originCenter = anchor.originCenter,
        width = LiquidGlassMorphSelectorDefaults.Width,
        height = LiquidGlassMorphSelectorDefaults.contentHeight(LocalDensity.current.fontScale),
        closeRequested = closeRequested,
        onDismiss = onDismiss,
        onFinished = onFinished,
        startContent = { DetailRow(title, returnValue, clickable = false) },
        content = { enabled ->
            LiquidGlassMorphSelector(
                title = title,
                options = listOf(ReadingStatus.WISH, ReadingStatus.READING, ReadingStatus.DONE),
                selected = selected,
                enabled = enabled && !saving,
                onSelect = onSelect,
                optionLabel = { readingStatusLabel(it) },
                optionModifier = { Modifier.testTag("reading-status-option-${it.toServerStatus()}") }
            )
        }
    )
}

@Composable
private fun readingStatusLabel(status: ReadingStatus): String = when (status) {
    ReadingStatus.WISH -> stringResource(R.string.reading_status_wish)
    ReadingStatus.READING -> stringResource(R.string.reading_status_reading)
    ReadingStatus.DONE -> stringResource(R.string.reading_status_done)
}
