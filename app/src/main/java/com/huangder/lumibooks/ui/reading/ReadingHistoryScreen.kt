package com.huangder.lumibooks.ui.reading

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.bookshelf.BookDetailsActivity
import com.huangder.lumibooks.ui.components.LiquidGlassAlertDialog
import com.huangder.lumibooks.ui.components.LiquidGlassBottomSheetScaffold
import com.huangder.lumibooks.ui.components.LiquidGlassIconButton
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadingHistoryScreen(
    onBack: () -> Unit,
    initialBookId: String? = null,
    initialDate: String? = null,
    viewModel: ReadingHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var filterDraft by remember { mutableStateOf(ReadingHistoryFilter()) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    var detailSession by remember { mutableStateOf<ReadingSessionItem?>(null) }
    var actionSession by remember { mutableStateOf<ReadingSessionItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ReadingSessionItem?>(null) }

    LaunchedEffect(initialBookId, initialDate) {
        viewModel.initialize(initialBookId, initialDate)
    }

    val filter = uiState.filter
    val bookLabel = filter.bookId
        ?.let { id -> uiState.books.firstOrNull { it.id == id }?.title }
        ?: stringResource(R.string.reading_history_all_books)
    val periodLabel = filter.date?.let { sessionDayLabel(it) }
        ?: stringResource(filter.period.labelRes)
    val modeLabel = when (filter.mode) {
        ReadingHistoryModeFilter.PAGED -> stringResource(R.string.reading_mode_paged)
        ReadingHistoryModeFilter.SCROLL -> stringResource(R.string.reading_mode_scroll)
        ReadingHistoryModeFilter.ALL -> stringResource(R.string.reading_history_all_modes)
    }

    Box(Modifier.fillMaxSize().background(AppColors.PageBg)) {
        ReadingHistoryTimelineContent(
            state = uiState,
            selectedPeriodLabel = periodLabel,
            selectedBookLabel = bookLabel,
            selectedModeLabel = modeLabel,
            onBack = onBack,
            onFilter = {
                filterDraft = uiState.filter
                filterOpen = true
            },
            onCalendar = { datePickerOpen = true },
            onOpenDay = viewModel::setDate,
            onOpenSession = { detailSession = it },
            onOpenBook = { BookDetailsActivity.start(context, it) },
            onLongPressSession = { actionSession = it },
            hasMore = uiState.hasMore,
            onLoadMore = viewModel::loadMore
        )
    }

    if (filterOpen) {
        ReadingHistoryFilterSheet(
            books = uiState.books,
            draft = filterDraft,
            onDraftChange = { filterDraft = it },
            onDismiss = { filterOpen = false },
            onApply = {
                viewModel.applyFilter(filterDraft)
                filterOpen = false
            },
            onReset = { filterDraft = ReadingHistoryFilter() }
        )
    }

    if (datePickerOpen) {
        val initialMillis = filter.date
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = pickerState.selectedDateMillis
                        if (millis != null) {
                            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                            viewModel.setDate(date)
                        }
                        datePickerOpen = false
                    }
                ) { Text(stringResource(R.string.confirm), color = AppColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) {
                    Text(stringResource(R.string.cancel), color = AppColors.TextPrimary)
                }
            }
        ) {
            DatePicker(
                state = pickerState,
                colors = DatePickerDefaults.colors().copy(
                    containerColor = AppColors.CardBg,
                    titleContentColor = AppColors.TextPrimary,
                    headlineContentColor = AppColors.TextPrimary,
                    weekdayContentColor = AppColors.TextSecondary,
                    subheadContentColor = AppColors.TextSecondary,
                    navigationContentColor = AppColors.TextPrimary,
                    yearContentColor = AppColors.TextPrimary,
                    selectedYearContentColor = AppColors.OnAccent,
                    selectedYearContainerColor = AppColors.Accent,
                    dayContentColor = AppColors.TextPrimary,
                    selectedDayContentColor = AppColors.OnAccent,
                    selectedDayContainerColor = AppColors.Accent,
                    todayDateBorderColor = AppColors.Accent,
                    dividerColor = AppColors.Divider
                )
            )
        }
    }

    detailSession?.let { item ->
        ReadingSessionDetailSheet(
            item = item,
            onDismiss = { detailSession = null },
            onSaveNote = { note, mood -> viewModel.saveNote(item.id, note, mood) }
        )
    }

    actionSession?.let { item ->
        ReadingSessionActionsSheet(
            item = item,
            onDismiss = { actionSession = null },
            onAddNote = {
                actionSession = null
                detailSession = item
            },
            onDelete = {
                actionSession = null
                deleteTarget = item
            }
        )
    }

    deleteTarget?.let { item ->
        LiquidGlassAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.reading_session_delete_title)) },
            text = { Text(stringResource(R.string.reading_session_delete_message)) },
            confirmButton = {
                LiquidGlassTextButton(
                    text = stringResource(R.string.delete),
                    onClick = {
                        viewModel.delete(item.id)
                        deleteTarget = null
                    }
                )
            },
            dismissButton = {
                LiquidGlassTextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { deleteTarget = null }
                )
            }
        )
    }
}

@Composable
private fun HistoryFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = AppType.BodySmall,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        color = if (active) AppColors.Accent else AppColors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(AppRoundedCornerShape(18.dp))
            .background(if (active) AppColors.Accent.copy(alpha = 0.14f) else AppColors.BgGray)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReadingHistoryRow(
    item: ReadingSessionItem,
    onClick: () -> Unit,
    onOpenBook: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Button
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.coverPath,
            contentDescription = item.bookTitle,
            modifier = Modifier
                .size(38.dp, 53.dp)
                .clip(AppRoundedCornerShape(4.dp))
                .background(AppColors.BgGray)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onOpenBook() },
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(AppSpace.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.bookTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onOpenBook() }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${clockTime(item.startTime)} · ${formatReadingDuration(item.duration)}",
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${chapterRangeLabel(item)} · ${progressRangeLabel(item)}",
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
internal fun ReadingHistoryFilterSheet(
    books: List<ReadingHistoryBookOption>,
    draft: ReadingHistoryFilter,
    onDraftChange: (ReadingHistoryFilter) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit
) {
    var bookQuery by rememberSaveable { mutableStateOf("") }
    val visibleBooks = books.filter { it.title.contains(bookQuery, ignoreCase = true) }

    LiquidGlassBottomSheetScaffold(
        onDismiss = onDismiss,
        maxHeightFraction = 0.82f,
        contentSurfaceColor = AppColors.CardBg,
        contentPadding = PaddingValues(0.dp)
    ) { requestDismiss ->
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = AppSpace.lg,
                    end = AppSpace.md,
                    top = AppSpace.md,
                    bottom = AppSpace.sm
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reading_history_filter_title),
                    fontSize = AppType.Section,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassIconButton(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.reading_session_close),
                    onClick = requestDismiss,
                    size = 44.dp,
                    iconSize = 18.dp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpace.lg)
            ) {
                FilterSectionTitle(stringResource(R.string.reading_history_filter_book))
                BasicTextField(
                    value = bookQuery,
                    onValueChange = { bookQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = AppType.BodySmall,
                        color = AppColors.TextPrimary
                    ),
                    cursorBrush = SolidColor(AppColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(AppRoundedCornerShape(12.dp))
                                .background(AppColors.BgGray)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (bookQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.reading_history_search_books),
                                    fontSize = AppType.BodySmall,
                                    color = AppColors.TextSecondary
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(AppSpace.sm))
                FilterOptionRow(
                    label = stringResource(R.string.reading_history_all_books),
                    selected = draft.bookId == null,
                    onClick = { onDraftChange(draft.copy(bookId = null)) }
                )
                visibleBooks.forEach { book ->
                    FilterOptionRow(
                        label = book.title,
                        selected = draft.bookId == book.id,
                        onClick = { onDraftChange(draft.copy(bookId = book.id)) }
                    )
                }

                Spacer(Modifier.height(AppSpace.md))
                FilterSectionTitle(stringResource(R.string.reading_history_filter_period))
                if (draft.date != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sessionDayLabel(draft.date),
                            fontSize = AppType.BodySmall,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.reading_history_clear_date),
                            fontSize = AppType.Caption,
                            color = AppColors.Accent,
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    onDraftChange(draft.copy(date = null, period = ReadingHistoryPeriod.ALL))
                                }
                                .padding(8.dp)
                        )
                    }
                }
                FilterChips(
                    options = ReadingHistoryPeriod.entries
                        .filter { it != ReadingHistoryPeriod.DAY }
                        .map { stringResource(it.labelRes) to it },
                    selected = draft.period,
                    onSelected = { onDraftChange(draft.copy(period = it, date = null)) }
                )

                Spacer(Modifier.height(AppSpace.md))
                FilterSectionTitle(stringResource(R.string.reading_history_filter_mode))
                FilterChips(
                    options = listOf(
                        stringResource(R.string.reading_history_all_modes) to ReadingHistoryModeFilter.ALL,
                        stringResource(R.string.reading_mode_paged) to ReadingHistoryModeFilter.PAGED,
                        stringResource(R.string.reading_mode_scroll) to ReadingHistoryModeFilter.SCROLL
                    ),
                    selected = draft.mode,
                    onSelected = { onDraftChange(draft.copy(mode = it)) }
                )
                Spacer(Modifier.height(AppSpace.md))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = AppSpace.lg,
                    vertical = AppSpace.md
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)
            ) {
                LiquidGlassTextButton(
                    text = stringResource(R.string.reading_history_reset),
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassTextButton(
                    text = stringResource(R.string.reading_history_apply),
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    tintedColor = AppColors.Accent,
                    contentColor = AppColors.OnAccent
                )
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = AppType.Caption,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextSecondary
    )
    Spacer(Modifier.height(AppSpace.xs))
}

@Composable
private fun FilterOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRoundedCornerShape(10.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = AppType.BodySmall,
            color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = AppColors.Accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun <T> FilterChips(
    options: List<Pair<String, T>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
        options.forEach { (label, value) ->
            val active = value == selected
            Text(
                text = label,
                fontSize = AppType.BodySmall,
                color = if (active) AppColors.Accent else AppColors.TextPrimary,
                modifier = Modifier
                    .clip(AppRoundedCornerShape(16.dp))
                    .background(if (active) AppColors.Accent.copy(alpha = 0.14f) else AppColors.BgGray)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        role = Role.Button
                    ) { onSelected(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ReadingSessionActionsSheet(
    item: ReadingSessionItem,
    onDismiss: () -> Unit,
    onAddNote: () -> Unit,
    onDelete: () -> Unit
) {
    LiquidGlassBottomSheetScaffold(
        onDismiss = onDismiss,
        maxHeightFraction = 0.42f,
        contentPadding = PaddingValues(horizontal = AppSpace.lg, vertical = AppSpace.md)
    ) { requestDismiss ->
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = item.bookTitle,
                fontSize = AppType.Body,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${sessionRelativeDateLabel(item.date)} · ${formatReadingDuration(item.duration)}",
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
            Spacer(Modifier.height(AppSpace.md))
            ActionSheetRow(
                icon = AppIcons.PencilSimple,
                label = stringResource(R.string.reading_session_edit_note),
                onClick = onAddNote
            )
            ActionSheetRow(
                icon = AppIcons.Trash,
                label = stringResource(R.string.reading_session_delete),
                onClick = onDelete
            )
            Spacer(Modifier.height(AppSpace.sm))
        }
    }
}

@Composable
private fun ActionSheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRoundedCornerShape(12.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.TextPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(AppSpace.md))
        Text(
            text = label,
            fontSize = AppType.Body,
            color = AppColors.TextPrimary
        )
    }
}

/** Stateless timeline used by both the screen and its Compose previews. */
@Composable
internal fun ReadingHistoryTimelineContent(
    state: ReadingHistoryUiState,
    selectedPeriodLabel: String,
    selectedBookLabel: String,
    selectedModeLabel: String,
    onBack: () -> Unit,
    onFilter: () -> Unit,
    onCalendar: () -> Unit,
    onOpenDay: (String) -> Unit,
    onOpenSession: (ReadingSessionItem) -> Unit,
    onOpenBook: (String) -> Unit,
    onLongPressSession: (ReadingSessionItem) -> Unit,
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.filter) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AppSpace.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassIconButton(
                imageVector = directionalIcon(AppIcons.ArrowLeft, AppIcons.ArrowRight),
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
                size = 44.dp,
                iconSize = 20.dp
            )
            Spacer(Modifier.width(AppSpace.sm))
            Text(
                text = stringResource(R.string.reading_history_title),
                fontSize = AppType.Section,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            LiquidGlassIconButton(
                imageVector = AppIcons.Calendar,
                contentDescription = stringResource(R.string.reading_history_calendar),
                onClick = onCalendar,
                size = 44.dp,
                iconSize = 20.dp
            )
            LiquidGlassIconButton(
                imageVector = AppIcons.Funnel,
                contentDescription = stringResource(R.string.reading_history_filter_title),
                onClick = onFilter,
                size = 44.dp,
                iconSize = 20.dp
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AppSpace.lg, vertical = AppSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)
        ) {
            HistoryFilterChip(selectedBookLabel, state.filter.bookId != null, onFilter)
            HistoryFilterChip(selectedPeriodLabel, state.filter.period != ReadingHistoryPeriod.ALL, onFilter)
            HistoryFilterChip(selectedModeLabel, state.filter.mode != ReadingHistoryModeFilter.ALL, onFilter)
        }
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.statistics_loading),
                    fontSize = AppType.BodySmall,
                    color = AppColors.TextSecondary
                )
            }
            return@Column
        }
        val groups = remember(state.sessions) { state.sessions.groupByDay() }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.reading_history_empty),
                        fontSize = AppType.BodySmall,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.reading_history_empty_hint),
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = AppSpace.lg,
                end = AppSpace.lg,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + AppSpace.lg
            )
        ) {
            groups.forEach { group ->
                item(key = "day_${group.date}") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.Button
                            ) { onOpenDay(group.date) }
                            .padding(top = AppSpace.md, bottom = AppSpace.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sessionRelativeDateLabel(group.date),
                            fontSize = AppType.BodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(
                                R.string.reading_history_day_total,
                                formatReadingDuration(group.totalDuration)
                            ),
                            fontSize = AppType.Caption,
                            color = AppColors.TextSecondary
                        )
                    }
                }
                items(
                    count = group.sessions.size,
                    key = { index ->
                        group.sessions[index].sessionKey.ifBlank { "id-${group.sessions[index].id}" }
                    }
                ) { index ->
                    val item = group.sessions[index]
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 50.dp)
                                .height(0.5.dp)
                                .background(AppColors.Divider)
                        )
                    }
                    ReadingHistoryRow(
                        item = item,
                        onClick = { onOpenSession(item) },
                        onOpenBook = { onOpenBook(item.bookId) },
                        onLongClick = { onLongPressSession(item) }
                    )
                }
            }
            if (hasMore) {
                item(key = "load_more") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = AppSpace.md),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = onLoadMore) {
                            Text(
                                text = stringResource(R.string.reading_history_load_more),
                                fontSize = AppType.BodySmall,
                                color = AppColors.Accent
                            )
                        }
                    }
                }
            }
        }
    }
}
