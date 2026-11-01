package com.huangder.lumibooks.ui.bookshelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.domain.model.DailyTotal
import com.huangder.lumibooks.domain.model.Note
import com.huangder.lumibooks.ui.components.InformationCard
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.huangder.lumibooks.ui.reading.ReadingSessionItem
import com.huangder.lumibooks.ui.reading.chapterRangeLabel
import com.huangder.lumibooks.ui.reading.formatReadingDuration
import com.huangder.lumibooks.ui.reading.monthDayLabel
import com.huangder.lumibooks.ui.reading.progressDeltaPercent
import com.huangder.lumibooks.ui.reading.sessionShortDayLabel
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

@Composable
internal fun BookReadingTab(
    book: Book,
    state: BookDetailsUiState,
    serverMeta: ServerBookMeta?,
    onOpenHistory: () -> Unit,
    onOpenAnnotations: () -> Unit,
    onOpenSession: (ReadingSessionItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.lg)) {
        BookReadingOverviewCard(
            book = state.book ?: book,
            state = state
        )
        if (serverMeta != null && serverMeta.hasReadingRecord()) {
            InformationCard(
                title = stringResource(R.string.book_reading_server_status),
                titleFontSize = 18.sp,
                contentPadding = PaddingValues(0.dp),
                verticalArrangement = Arrangement.Top
            ) {
                ReadingRecordBlock(serverMeta)
            }
        }
        BookReadingJourneyCard(
            state = state,
            onOpenHistory = onOpenHistory,
            onOpenSession = onOpenSession
        )
        BookReadingProgressCard(state.sessions)
        BookAnnotationsPreviewCard(
            notes = state.notes,
            bookmarkCount = state.bookmarkCount,
            onOpenAnnotations = onOpenAnnotations
        )
    }
}

@Composable
private fun BookReadingOverviewCard(
    book: Book,
    state: BookDetailsUiState
) {
    val dailyTotalMs = state.dailyTotals.sumOf { it.totalDuration }
    val contributedMs = state.sessions.sumOf { it.contributedDuration }
    val coverageComplete = dailyTotalMs - contributedMs <= 1_000L
    val effectiveSessions = state.sessions.filter { it.isEffective }
    val sessionCount = when {
        !coverageComplete && dailyTotalMs > 0L -> null
        state.sessions.isNotEmpty() -> effectiveSessions.size
        state.dailyTotals.any { it.totalDuration > 0L } -> null
        else -> 0
    }
    val firstDate = state.sessions.minByOrNull { it.startTime }?.date
        ?: state.dailyTotals.lastOrNull { it.totalDuration > 0L }?.date
    val lastDateValue = state.sessions.maxByOrNull { it.startTime }?.date?.let { sessionDateText(it) }
        ?: state.dailyTotals.firstOrNull { it.totalDuration > 0L }?.date?.let { sessionDateText(it) }
        ?: book.lastReadTime.takeIf { it > 0L }?.let { dateText(it) }
        ?: "—"

    InformationCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
        Column(Modifier.fillMaxWidth().padding(AppSpace.md)) {
            Text(
                text = stringResource(R.string.book_reading_overview),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.height(AppSpace.md))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = formatReadingDuration(state.totalReadingTime),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.book_reading_total),
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.streak_days, state.activeDays),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = stringResource(R.string.book_detail_active_days),
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(AppSpace.md))
            Row(Modifier.fillMaxWidth()) {
                AuxiliaryMetric(
                    value = sessionCount?.let { stringResource(R.string.book_reading_session_count_value, it) } ?: "—",
                    label = stringResource(R.string.book_reading_sessions),
                    modifier = Modifier.weight(1f)
                )
                AuxiliaryMetric(
                    value = state.noteCount.toString(),
                    label = stringResource(R.string.book_detail_notes),
                    modifier = Modifier.weight(1f)
                )
                AuxiliaryMetric(
                    value = state.bookmarkCount.toString(),
                    label = stringResource(R.string.book_detail_bookmarks),
                    modifier = Modifier.weight(1f)
                )
            }
            if (!coverageComplete && dailyTotalMs > 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.book_reading_legacy_hint),
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.Divider))
        DetailRow(
            stringResource(R.string.book_reading_started),
            firstDate?.let { sessionDateText(it) } ?: "—",
            false
        )
        if (!book.isCloudOnly) {
            DetailRow(
                stringResource(R.string.book_detail_last_read),
                lastDateValue,
                false
            )
        }
    }
}

@Composable
private fun AuxiliaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookReadingJourneyCard(
    state: BookDetailsUiState,
    onOpenHistory: () -> Unit,
    onOpenSession: (ReadingSessionItem) -> Unit
) {
    val sessions = state.sessions.filter { it.isEffective }.take(3)
    val daily = state.dailyTotals.filter { it.totalDuration > 0L }.take(3)
    InformationCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpace.md, end = AppSpace.sm, top = AppSpace.md, bottom = AppSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.book_reading_journey),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            if (state.sessions.isNotEmpty()) {
                ViewAllRow(onClick = onOpenHistory)
            }
        }

        when {
            sessions.isNotEmpty() -> {
                sessions.forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 48.dp, end = AppSpace.md)
                                .height(0.5.dp)
                                .background(AppColors.Divider)
                        )
                    }
                    JourneySessionRow(item = item, onClick = { onOpenSession(item) })
                }
                Spacer(Modifier.height(AppSpace.xs))
            }
            daily.isNotEmpty() -> {
                Column(Modifier.padding(horizontal = AppSpace.md).padding(bottom = AppSpace.md)) {
                    Text(
                        text = stringResource(R.string.book_reading_daily_summary),
                        fontSize = AppType.BodySmall,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.book_reading_daily_summary_hint),
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary
                    )
                    Spacer(Modifier.height(AppSpace.sm))
                    daily.forEach { total ->
                        DailySummaryRow(total)
                    }
                }
            }
            else -> {
                Text(
                    text = stringResource(R.string.book_reading_empty_journey),
                    fontSize = AppType.BodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = AppSpace.md).padding(bottom = AppSpace.md)
                )
            }
        }
    }
}

@Composable
private fun JourneySessionRow(item: ReadingSessionItem, onClick: () -> Unit) {
    val delta = item.progressDeltaPercent()
    val chapter = chapterRangeLabel(item)
    val progress = when {
        delta != null && delta > 0 -> stringResource(R.string.book_reading_progress_delta, delta)
        else -> stringResource(
            R.string.book_reading_progress_value,
            (item.endProgress * 100f).toInt().coerceIn(0, 100)
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = AppSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = AppIcons.BookOpen,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(AppSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.book_reading_event,
                    sessionShortDayLabel(item.date),
                    formatReadingDuration(item.duration)
                ),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$chapter · $progress",
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note,
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(AppSpace.sm))
        Icon(
            imageVector = directionalIcon(AppIcons.CaretRight, AppIcons.CaretLeft),
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun DailySummaryRow(total: DailyTotal) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = sessionShortDayLabel(total.date),
            fontSize = AppType.BodySmall,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatReadingDuration(total.totalDuration),
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary
        )
    }
}

@Composable
private fun ViewAllRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.book_reading_view_all),
            fontSize = AppType.Caption,
            color = AppColors.Accent
        )
        Icon(
            imageVector = directionalIcon(AppIcons.CaretRight, AppIcons.CaretLeft),
            contentDescription = null,
            tint = AppColors.Accent,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun BookReadingProgressCard(sessions: List<ReadingSessionItem>) {
    val points = remember(sessions) { buildProgressPoints(sessions.filter { it.isEffective }) }
    if (points.size < 2) return

    InformationCard(contentPadding = PaddingValues(AppSpace.md), verticalArrangement = Arrangement.Top) {
        Text(
            text = stringResource(R.string.book_reading_progress_change),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(AppSpace.md))
        val first = points.first()
        val last = points.last()
        val firstLabel = runCatching { LocalDate.parse(first.date) }.getOrNull()?.let { monthDayLabel(it) }
        val lastLabel = runCatching { LocalDate.parse(last.date) }.getOrNull()?.let { monthDayLabel(it) }
        ProgressChart(points, firstLabel, lastLabel)
    }
}

private data class ProgressPoint(val date: String, val progress: Float)

private fun buildProgressPoints(sessions: List<ReadingSessionItem>): List<ProgressPoint> {
    return sessions
        .filter { it.endProgress > 0f }
        .groupBy { it.date }
        .mapNotNull { (date, items) -> items.maxByOrNull { it.startTime }?.let { ProgressPoint(date, it.endProgress) } }
        .sortedBy { it.date }
}

@Composable
private fun ProgressChart(points: List<ProgressPoint>, firstLabel: String?, lastLabel: String?) {
    val accent = AppColors.Accent
    val divider = AppColors.Divider
    val secondary = AppColors.TextSecondary
    val segments = remember(points) {
        val result = mutableListOf<MutableList<ProgressPoint>>()
        var current = mutableListOf<ProgressPoint>()
        points.forEachIndexed { index, point ->
            if (index > 0 && point.progress + 0.03f < points[index - 1].progress) {
                if (current.isNotEmpty()) result += current
                current = mutableListOf()
            }
            current += point
        }
        if (current.isNotEmpty()) result += current
        result
    }
    val description = stringResource(R.string.book_reading_progress_change)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = description }
    ) {
        val leftPad = 34.dp.toPx()
        val topPad = 8.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - topPad - bottomPad
        val paint = android.graphics.Paint().apply {
            textSize = 11.sp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
            color = secondary.toArgbCompat()
        }
        listOf(1f, 0.5f, 0f).forEach { fraction ->
            val y = topPad + chartHeight * (1f - fraction)
            drawLine(
                color = divider,
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 0.5.dp.toPx()
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${(fraction * 100).toInt()}%",
                leftPad - 6.dp.toPx(),
                y + 4.dp.toPx(),
                paint
            )
        }
        fun xAt(index: Int): Float =
            if (points.size == 1) leftPad + chartWidth / 2f
            else leftPad + chartWidth * index / (points.size - 1).toFloat()
        fun yAt(progress: Float): Float = topPad + chartHeight * (1f - progress.coerceIn(0f, 1f))

        val globalIndexByPoint = points.withIndex().associate { it.value to it.index }
        segments.forEach { segment ->
            if (segment.size > 1) {
                val path = Path()
                segment.forEachIndexed { index, point ->
                    val x = xAt(globalIndexByPoint.getValue(point))
                    val y = yAt(point.progress)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            segment.forEach { point ->
                drawCircle(
                    color = accent,
                    radius = 2.5.dp.toPx(),
                    center = Offset(xAt(globalIndexByPoint.getValue(point)), yAt(point.progress))
                )
            }
        }

        paint.textAlign = android.graphics.Paint.Align.LEFT
        firstLabel?.let {
            drawContext.canvas.nativeCanvas.drawText(it, leftPad, size.height - 4.dp.toPx(), paint)
        }
        if (lastLabel != null && lastLabel != firstLabel) {
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                lastLabel,
                size.width,
                size.height - 4.dp.toPx(),
                paint
            )
        }
    }
}

@Composable
private fun BookAnnotationsPreviewCard(
    notes: List<Note>,
    bookmarkCount: Int,
    onOpenAnnotations: () -> Unit
) {
    val textNotes = remember(notes) {
        notes.filterNot { it.isInkStroke }.sortedByDescending { it.createdAt }
    }
    InformationCard(contentPadding = PaddingValues(0.dp), verticalArrangement = Arrangement.Top) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpace.md, end = AppSpace.sm, top = AppSpace.md, bottom = AppSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.book_reading_annotations),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    text = stringResource(R.string.book_reading_notes_count, textNotes.size) +
                        " · " + stringResource(R.string.book_reading_bookmarks_count, bookmarkCount),
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary
                )
            }
            ViewAllRow(onClick = onOpenAnnotations)
        }

        if (textNotes.isEmpty()) {
            Text(
                text = stringResource(R.string.book_reading_annotations_empty),
                fontSize = AppType.BodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = AppSpace.md).padding(bottom = AppSpace.md)
            )
        } else {
            textNotes.take(3).forEachIndexed { index, note ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpace.md)
                            .height(0.5.dp)
                            .background(AppColors.Divider)
                    )
                }
                AnnotationPreviewRow(
                    note = note,
                    onClick = onOpenAnnotations
                )
            }
            Spacer(Modifier.height(AppSpace.xs))
        }
    }
}

@Composable
private fun AnnotationPreviewRow(note: Note, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = AppSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 40.dp)
                .background(AppColors.Accent.copy(alpha = 0.5f), AppRoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(AppSpace.sm))
        Column(Modifier.weight(1f)) {
            val excerpt = note.selectedText.ifBlank { note.note }
            Text(
                text = excerpt,
                fontSize = AppType.BodySmall,
                lineHeight = 20.sp,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (note.note.isNotBlank() && note.note != excerpt) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note.note,
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chapter_number, note.chapterIndex + 1) +
                    " · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(note.createdAt)),
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
        }
    }
}

private fun ServerBookMeta.hasReadingRecord(): Boolean =
    readLogs.isNotEmpty() || startDate != null || finishDate != null || readCount > 0

private fun dateText(timestamp: Long): String =
    if (timestamp <= 0L) "—"
    else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))

@Composable
private fun sessionDateText(rawDate: String): String {
    val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return rawDate
    return monthDayLabel(date)
}

private fun androidx.compose.ui.graphics.Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
