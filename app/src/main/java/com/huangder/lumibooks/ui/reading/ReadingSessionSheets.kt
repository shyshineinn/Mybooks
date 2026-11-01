package com.huangder.lumibooks.ui.reading

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.components.LiquidGlassBottomSheetScaffold
import com.huangder.lumibooks.ui.components.LiquidGlassIconButton
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

data class ReadingSessionSummary(
    val durationMillis: Long,
    val progressDeltaPercent: Int?,
    val annotationCount: Int,
    val sessionId: Long? = null
)

private data class MoodOption(val key: String, @StringRes val label: Int)

private val moodOptions = listOf(
    MoodOption("calm", R.string.mood_calm),
    MoodOption("rewarding", R.string.mood_rewarding),
    MoodOption("confused", R.string.mood_confused),
    MoodOption("moved", R.string.mood_moved)
)

@Composable
fun formatReadingDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).toInt()
    return when {
        totalMinutes >= 60 -> stringResource(
            R.string.reading_duration_hours_minutes,
            totalMinutes / 60,
            totalMinutes % 60
        )
        totalMinutes > 0 -> stringResource(R.string.reading_duration_minutes, totalMinutes)
        durationMillis > 0 -> stringResource(R.string.reading_duration_less_than_minute)
        else -> stringResource(R.string.reading_duration_minutes, 0)
    }
}

/** Compact unit text for dense metric columns; full sentences use formatReadingDuration. */
@Composable
fun formatReadingDurationCompact(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).toInt()
    val hourUnit = stringResource(R.string.unit_hour)
    val minuteUnit = stringResource(R.string.unit_minute)
    return when {
        totalMinutes >= 60 -> {
            val hours = totalMinutes / 60
            "$hours$hourUnit${totalMinutes % 60}$minuteUnit"
        }
        totalMinutes > 0 -> "$totalMinutes$minuteUnit"
        durationMillis > 0 -> stringResource(R.string.reading_duration_less_than_minute)
        else -> "0$minuteUnit"
    }
}

@Composable
fun readingModeLabel(mode: String): String = when (mode) {
    "continuous", "scroll", "vertical" -> stringResource(R.string.reading_mode_scroll)
    "slide", "none", "cover" -> stringResource(R.string.reading_mode_paged)
    else -> stringResource(R.string.reading_mode_reading)
}

private fun sessionLocalDate(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

@Composable
fun monthDayLabel(date: LocalDate): String =
    stringResource(R.string.reading_history_date, date.monthValue, date.dayOfMonth)

@Composable
fun sessionDayLabel(date: String): String {
    val local = sessionLocalDate(date) ?: return date
    return monthDayLabel(local)
}

@Composable
fun sessionRelativeDateLabel(date: String): String {
    val local = sessionLocalDate(date) ?: return date
    val today = LocalDate.now()
    return when (local) {
        today -> stringResource(R.string.reading_history_today, monthDayLabel(local))
        today.minusDays(1) -> stringResource(R.string.reading_history_yesterday, monthDayLabel(local))
        else -> monthDayLabel(local)
    }
}

@Composable
fun sessionShortDayLabel(date: String): String {
    val local = sessionLocalDate(date) ?: return date
    val today = LocalDate.now()
    return when (local) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> monthDayLabel(local)
    }
}

@Composable
fun sessionRecentTimeLabel(timestamp: Long): String {
    val local = runCatching {
        Date(timestamp).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull() ?: return clockTime(timestamp)
    val clock = clockTime(timestamp)
    val today = LocalDate.now()
    return when (local) {
        today -> stringResource(R.string.time_today_at, clock)
        today.minusDays(1) -> stringResource(R.string.time_yesterday_at, clock)
        else -> stringResource(R.string.date_month_day_time, local.monthValue, local.dayOfMonth, clock)
    }
}

fun clockTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

@Composable
fun chapterRangeLabel(item: ReadingSessionItem): String {
    val endTitle = item.endChapterTitle?.takeIf { it.isNotBlank() }
    val startTitle = item.startChapterTitle?.takeIf { it.isNotBlank() }
    return when {
        item.startChapterIndex != item.endChapterIndex ->
            stringResource(R.string.book_reading_chapter_transition, item.startChapterIndex + 1, item.endChapterIndex + 1)
        endTitle != null -> endTitle
        startTitle != null -> startTitle
        else -> stringResource(R.string.reading_session_chapter_single, item.endChapterIndex + 1)
    }
}

@Composable
fun progressRangeLabel(item: ReadingSessionItem): String {
    val start = (item.startProgress * 100f).toInt().coerceIn(0, 100)
    val end = (item.endProgress * 100f).toInt().coerceIn(0, 100)
    return if (start != end) {
        stringResource(R.string.reading_session_progress_range, start, end)
    } else {
        stringResource(R.string.book_reading_progress_value, end)
    }
}

/** One session's read-only detail, with an optional note editor. */
@Composable
fun ReadingSessionDetailSheet(
    item: ReadingSessionItem,
    onDismiss: () -> Unit,
    onSaveNote: (note: String?, mood: String?) -> Unit
) {
    LiquidGlassBottomSheetScaffold(
        onDismiss = onDismiss,
        maxHeightFraction = 0.8f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = AppSpace.lg,
            vertical = AppSpace.md
        )
    ) { requestDismiss ->
        ReadingSessionDetailContent(
            item = item,
            onClose = requestDismiss,
            onSaveNote = onSaveNote
        )
    }
}

@Composable
internal fun ReadingSessionDetailContent(
    item: ReadingSessionItem,
    onClose: () -> Unit,
    onSaveNote: (note: String?, mood: String?) -> Unit
) {
    var editing by rememberSaveable(item.id) { mutableStateOf(item.note.isNullOrBlank()) }
    var noteText by rememberSaveable(item.id) { mutableStateOf(item.note.orEmpty()) }
    var mood by rememberSaveable(item.id) { mutableStateOf(item.mood.orEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reading_session_sheet_title),
                    fontSize = AppType.Section,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassIconButton(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.reading_session_close),
                    onClick = onClose,
                    size = 44.dp,
                    iconSize = 18.dp
                )
            }
            Spacer(Modifier.height(AppSpace.sm))
            Text(
                text = formatReadingDuration(item.duration),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
            Text(
                text = stringResource(R.string.reading_session_duration),
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
            Spacer(Modifier.height(AppSpace.md))

            SessionInfoRow(stringResource(R.string.reading_session_date), sessionRelativeDateLabel(item.date))
            SessionInfoRow(
                stringResource(R.string.reading_session_start),
                "${clockTime(item.startTime)} · ${stringResource(R.string.reading_session_end)} ${clockTime(item.endTime)}"
            )
            SessionInfoRow(stringResource(R.string.reading_session_mode), readingModeLabel(item.mode))
            SessionInfoRow(stringResource(R.string.reading_session_chapters), chapterRangeLabel(item))
            SessionInfoRow(stringResource(R.string.reading_session_progress), progressRangeLabel(item))

            Spacer(Modifier.height(AppSpace.md))
            Text(
                stringResource(R.string.reading_session_mood),
                fontSize = AppType.BodySmall,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary
            )
            Spacer(Modifier.height(AppSpace.sm))
            MoodSelector(selected = mood, onSelected = { mood = it })

            Spacer(Modifier.height(AppSpace.md))
            Text(
                stringResource(R.string.reading_session_note),
                fontSize = AppType.BodySmall,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary
            )
            Spacer(Modifier.height(AppSpace.sm))
            if (editing) {
                SessionNoteField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = stringResource(R.string.reading_session_note_placeholder)
                )
                Spacer(Modifier.height(AppSpace.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
                    LiquidGlassTextButton(
                        text = stringResource(R.string.save),
                        onClick = {
                            onSaveNote(noteText.trim().ifBlank { null }, mood.ifBlank { null })
                            editing = false
                        },
                        tintedColor = AppColors.Accent,
                        contentColor = AppColors.OnAccent
                    )
                    LiquidGlassTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            noteText = item.note.orEmpty()
                            mood = item.mood.orEmpty()
                            editing = false
                        }
                    )
                }
            } else {
                if (item.note.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.reading_session_add_note),
                        fontSize = AppType.BodySmall,
                        color = AppColors.Accent,
                        modifier = Modifier
                            .clip(AppRoundedCornerShape(12.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { editing = true }
                            .padding(vertical = 10.dp)
                    )
                } else {
                    Text(
                        text = noteText,
                        fontSize = AppType.BodySmall,
                        lineHeight = 22.sp,
                        color = AppColors.TextPrimary
                    )
                    Spacer(Modifier.height(AppSpace.xs))
                    Text(
                        text = stringResource(R.string.reading_session_add_note),
                        fontSize = AppType.Caption,
                        color = AppColors.Accent,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { editing = true }
                            .padding(vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(AppSpace.sm))
    }
}

@Composable
private fun SessionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(AppSpace.md))
        Text(
            text = value,
            fontSize = AppType.BodySmall,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MoodSelector(selected: String, onSelected: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
    ) {
        moodOptions.forEach { option ->
            val active = option.key == selected
            Text(
                text = stringResource(option.label),
                fontSize = AppType.BodySmall,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) AppColors.TextPrimary else AppColors.TextSecondary,
                modifier = Modifier
                    .clip(AppRoundedCornerShape(20.dp))
                    .background(if (active) AppColors.BgGray else AppColors.PageBg)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onSelected(if (active) "" else option.key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SessionNoteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(AppRoundedCornerShape(12.dp))
            .background(AppColors.BgGray)
            .padding(12.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = AppType.BodySmall,
                color = AppColors.TextSecondary
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(500)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = AppColors.TextPrimary,
                fontSize = AppType.BodySmall,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(AppColors.Accent)
        )
    }
}

/** Shown after a saved session when the setting allows it. Nothing here is required. */
@Composable
fun ReadingSessionSummarySheet(
    summary: ReadingSessionSummary,
    onDone: (note: String?, mood: String?) -> Unit
) {
    LiquidGlassBottomSheetScaffold(
        onDismiss = { onDone(null, null) },
        maxHeightFraction = 0.7f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = AppSpace.lg,
            vertical = AppSpace.md
        )
    ) { _ ->
        ReadingSessionSummaryContent(
            summary = summary,
            onClose = { onDone(null, null) },
            onDone = onDone
        )
    }
}

@Composable
internal fun ReadingSessionSummaryContent(
    summary: ReadingSessionSummary,
    onClose: () -> Unit,
    onDone: (note: String?, mood: String?) -> Unit
) {
    var noteText by rememberSaveable { mutableStateOf("") }
    var mood by rememberSaveable { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.reading_summary_title),
                    fontSize = AppType.Section,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassIconButton(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.reading_session_close),
                    onClick = onClose,
                    size = 44.dp,
                    iconSize = 18.dp
                )
            }
            Spacer(Modifier.height(AppSpace.md))
            Row(Modifier.fillMaxWidth()) {
                SummaryMetric(
                    value = formatReadingDuration(summary.durationMillis),
                    label = stringResource(R.string.reading_session_duration),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    value = summary.progressDeltaPercent?.let { delta ->
                        if (delta > 0) "+$delta%" else "$delta%"
                    } ?: "—",
                    label = stringResource(R.string.reading_summary_progress),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    value = stringResource(R.string.reading_summary_count, summary.annotationCount),
                    label = stringResource(R.string.reading_summary_annotations),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(AppSpace.md))
            Text(
                stringResource(R.string.reading_session_mood),
                fontSize = AppType.BodySmall,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary
            )
            Spacer(Modifier.height(AppSpace.sm))
            MoodSelector(selected = mood, onSelected = { mood = it })
            Spacer(Modifier.height(AppSpace.md))
            SessionNoteField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = stringResource(R.string.reading_session_note_placeholder)
            )
            Spacer(Modifier.height(AppSpace.md))
            LiquidGlassTextButton(
                text = stringResource(R.string.reading_summary_done),
                onClick = { onDone(noteText.trim().ifBlank { null }, mood.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                tintedColor = AppColors.Accent,
                contentColor = AppColors.OnAccent
            )
            Spacer(Modifier.height(AppSpace.sm))
        }
}

@Composable
private fun SummaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
