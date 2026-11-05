package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.animation.VisualMotion.tween
import com.huangder.lumibooks.ui.components.AppMonthCalendar
import com.huangder.lumibooks.ui.components.AppMonthCalendarMode
import com.huangder.lumibooks.ui.components.LiquidGlassBottomSheetScaffold
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ReadingTimelineDateDialog(
    event: ReadingTimelineEvent,
    saving: Boolean,
    ready: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (LocalDate) -> Unit
) {
    var selected by remember(event) { mutableStateOf(event.date) }
    var expanded by remember(event) { mutableStateOf(false) }
    var month by remember(event) { mutableStateOf(YearMonth.from(event.date)) }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    val motion = LocalMotionEnabled.current && !LocalEInkMode.current
    val dateLabel = stringResource(if (event.type == ReadingTimelineEventType.START)
        R.string.reading_timeline_start_time else R.string.reading_timeline_finish_time)
    val dateText = selected.format(formatter)
    val textMeasurer = rememberTextMeasurer()
    val dateStyle = LocalTextStyle.current.copy(fontSize = AppType.BodySmall)
    LiquidGlassBottomSheetScaffold(
        onDismiss = onDismiss,
        onRequestDismiss = { !saving },
        maxHeightFraction = .85f,
        glassContentScrimColor = AppColors.CardBg.copy(alpha = .24f),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = AppSpace.md)
    ) { requestDismiss ->
        Column(Modifier.fillMaxWidth()
            .then(if (motion) Modifier.animateContentSize(tween(200)) else Modifier)
            .verticalScroll(rememberScrollState()).testTag("timeline_date_dialog"),
            verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
            Text(stringResource(R.string.reading_timeline_edit_time), fontSize = AppType.Section,
                fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = !saving, role = Role.Button) {
                    if (!expanded) month = YearMonth.from(selected)
                    expanded = !expanded
                }.testTag("timeline_date_value")) {
                val density = LocalDensity.current
                val gap = AppSpace.sm
                val fontSize = remember(dateLabel, dateText, dateStyle, maxWidth, density, textMeasurer) {
                    val available = with(density) { (maxWidth - gap).toPx() } - 2f
                    fun fits(size: Float): Boolean {
                        val style = dateStyle.copy(fontSize = size.sp)
                        return textMeasurer.measure(dateLabel, style = style, softWrap = false).size.width +
                            textMeasurer.measure(dateText, style = style, softWrap = false).size.width <= available
                    }
                    if (fits(AppType.BodySmall.value)) AppType.BodySmall else {
                        // Measure candidates: letter spacing and font hinting do not scale linearly.
                        var low = 1f
                        var high = AppType.BodySmall.value
                        repeat(10) {
                            val candidate = (low + high) / 2f
                            if (fits(candidate)) low = candidate else high = candidate
                        }
                        low.sp
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel, style = dateStyle.copy(fontSize = fontSize), maxLines = 1, softWrap = false,
                        color = AppColors.TextSecondary, modifier = Modifier.testTag("timeline_date_label"))
                    Spacer(Modifier.width(gap))
                    Text(dateText, style = dateStyle.copy(fontSize = fontSize), maxLines = 1, softWrap = false,
                        textAlign = TextAlign.End, color = AppColors.TextPrimary,
                        modifier = Modifier.weight(1f).testTag("timeline_date_text"))
                }
            }
            if (expanded) AppMonthCalendar(
                month = month,
                onPreviousMonth = { month = month.minusMonths(1) },
                onNextMonth = { month = month.plusMonths(1) },
                previousEnabled = month > YearMonth.of(1, 1),
                nextEnabled = month < YearMonth.of(9999, 12),
                mode = AppMonthCalendarMode.DatePicker,
                selectedDate = selected,
                onDateSelected = { selected = it; expanded = false }
            )
            error?.let { Text(it, color = AppColors.TextSecondary, fontSize = AppType.Caption,
                modifier = Modifier.testTag("timeline_date_error")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpace.sm, Alignment.End)) {
                LiquidGlassTextButton(text = stringResource(R.string.cancel), onClick = requestDismiss,
                    contentColor = AppColors.TextSecondary, modifier = Modifier.testTag("timeline_date_cancel"))
                LiquidGlassTextButton(text = stringResource(R.string.confirm), tintedColor = AppColors.Accent,
                    onClick = {
                        if (!saving) {
                            if (selected == event.date) requestDismiss()
                            else if (ready) onSave(selected)
                        }
                    }, modifier = Modifier.testTag("timeline_date_save"))
            }
        }
    }
}
