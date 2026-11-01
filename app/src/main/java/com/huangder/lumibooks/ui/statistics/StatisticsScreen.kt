package com.huangder.lumibooks.ui.statistics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.animation.OverscrollBounce
import com.huangder.lumibooks.ui.animation.PageEntranceItem
import com.huangder.lumibooks.ui.animation.VisualMotion.tween
import com.huangder.lumibooks.ui.animation.mainTabEntrance
import com.huangder.lumibooks.ui.animation.pressEffect
import com.huangder.lumibooks.ui.bookshelf.AnnotationFeedActivity
import com.huangder.lumibooks.ui.bookshelf.BookDetailsActivity
import com.huangder.lumibooks.ui.components.InformationCard
import com.huangder.lumibooks.ui.components.LiquidGlassSegmentedControl
import com.huangder.lumibooks.ui.components.StatusGradientOverlay
import com.huangder.lumibooks.ui.components.lumiCardSurface
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.icons.directionalIcon
import com.huangder.lumibooks.ui.reading.ReadingHistoryActivity
import com.huangder.lumibooks.ui.reading.ReadingSessionDetailSheet
import com.huangder.lumibooks.ui.reading.ReadingSessionItem
import com.huangder.lumibooks.ui.reading.formatReadingDuration
import com.huangder.lumibooks.ui.reading.formatReadingDurationCompact
import com.huangder.lumibooks.ui.reading.progressDeltaPercent
import com.huangder.lumibooks.ui.reading.sessionDayLabel
import com.huangder.lumibooks.ui.reading.sessionRecentTimeLabel
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.KaiTi
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalEInkMode
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import com.huangder.lumibooks.ui.theme.LocalUseMaterial3Theme
import com.huangder.lumibooks.ui.theme.cardOutline
import com.huangder.lumibooks.ui.theme.drawAppShape
import com.huangder.lumibooks.ui.theme.resolveAppFontFamily
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

@Composable
fun StatisticsScreen(
    playEntranceAnimation: Boolean = false,
    onMessage: (String) -> Unit = {},
    viewModel: StatisticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isLiquidGlass = LocalAppTheme.current == "liquid_glass"
    val topBlurBackdrop = rememberLayerBackdrop()
    val segmentedControlBackdrop = rememberLayerBackdrop()
    val statusBarTopPadding = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
    val bottomContentPadding = if (LocalUseMaterial3Theme.current) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp
    } else {
        120.dp
    }
    val motionEnabled = LocalMotionEnabled.current
    val context = LocalContext.current
    val chartAnimationsPlayed = remember { mutableStateListOf(false, false, false) }
    val selectedPeriod = uiState.selectedTab.coerceIn(0, 2)
    val playChartAnimation = remember(selectedPeriod) {
        !chartAnimationsPlayed[selectedPeriod]
    }
    var detailSession by remember { mutableStateOf<ReadingSessionItem?>(null) }

    LaunchedEffect(selectedPeriod) {
        chartAnimationsPlayed[selectedPeriod] = true
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            viewModel.clearError()
            onMessage(message)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppColors.PageBg)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isLiquidGlass) Modifier.layerBackdrop(segmentedControlBackdrop)
                    else Modifier
                )
                .background(AppColors.PageBg)
        )
        OverscrollBounce(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isLiquidGlass) Modifier.layerBackdrop(topBlurBackdrop) else Modifier
                )
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                item(key = "header") {
                    Spacer(Modifier.height(statusBarTopPadding + AppSpace.md))
                    PageEntranceItem(play = playEntranceAnimation, index = 0) {
                        Text(
                            text = stringResource(R.string.stats_title),
                            fontSize = AppType.Display,
                            fontWeight = FontWeight.Bold,
                            fontFamily = resolveAppFontFamily(KaiTi),
                            letterSpacing = (-0.02).sp,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = AppSpace.lg)
                        )
                    }
                    Spacer(Modifier.height(AppSpace.lg))
                }

                item(key = "tabs") {
                    val tabs = listOf(
                        stringResource(R.string.tab_week),
                        stringResource(R.string.tab_month),
                        stringResource(R.string.tab_year)
                    )
                    PageEntranceItem(play = playEntranceAnimation, index = 1) {
                        PeriodSegmentedControl(
                            labels = tabs,
                            selectedIndex = selectedPeriod,
                            onSelected = viewModel::selectTab,
                            backdrop = segmentedControlBackdrop.takeIf { isLiquidGlass }
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }

                item(key = "summary") {
                    PageEntranceItem(play = playEntranceAnimation, index = 2) {
                        ReadingSummary(uiState = uiState, selectedPeriod = selectedPeriod)
                    }
                    Spacer(Modifier.height(AppSpace.lg))
                }

                item(key = "overview") {
                    AnimatedContent(
                        targetState = selectedPeriod,
                        transitionSpec = {
                            val duration = if (motionEnabled) 190 else 100
                            fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
                        },
                        label = "statisticsPeriod"
                    ) { period ->
                        Column(Modifier.mainTabEntrance()) {
                            when (period) {
                                0 -> WeeklyOverview(
                                    uiState = uiState,
                                    animateChart = period == selectedPeriod && playChartAnimation,
                                    onPrevious = viewModel::previousWeek,
                                    onNext = viewModel::nextWeek,
                                    onOpenDay = { date -> ReadingHistoryActivity.start(context, date = date) }
                                )
                                1 -> MonthlyHeatmap(
                                    uiState = uiState,
                                    onPrevious = viewModel::previousMonth,
                                    onNext = viewModel::nextMonth,
                                    onOpenDay = { date -> ReadingHistoryActivity.start(context, date = date) }
                                )
                                else -> YearlyHeatmap(
                                    uiState = uiState,
                                    onPrevious = viewModel::previousYear,
                                    onNext = viewModel::nextYear,
                                    onOpenDay = { date -> ReadingHistoryActivity.start(context, date = date) }
                                )
                            }
                            Spacer(Modifier.height(AppSpace.lg))
                        }
                    }
                }

                item(key = "recent") {
                    PageEntranceItem(play = playEntranceAnimation, index = 3) {
                        Column {
                            RecentReadingSection(
                                sessions = uiState.recentSessions,
                                loading = uiState.recentSessionsLoading,
                                onOpenAll = { ReadingHistoryActivity.start(context) },
                                onOpenSession = { detailSession = it }
                            )
                            Spacer(Modifier.height(AppSpace.lg))
                        }
                    }
                }

                item(key = "annotations") {
                    PageEntranceItem(play = playEntranceAnimation, index = 4) {
                        Column {
                            AnnotationsOverview(
                                summary = uiState.annotationSummary,
                                onOpen = { AnnotationFeedActivity.start(context) }
                            )
                            Spacer(Modifier.height(AppSpace.lg))
                        }
                    }
                }

                item(key = "most_read") {
                    PageEntranceItem(play = playEntranceAnimation, index = 5) {
                        Column {
                            MostReadBooks(
                                books = uiState.mostReadBooks,
                                onOpen = { bookId -> BookDetailsActivity.start(context, bookId) }
                            )
                            Spacer(Modifier.height(AppSpace.lg))
                        }
                    }
                }

                item(key = "streak") {
                    PageEntranceItem(play = playEntranceAnimation, index = 6) {
                        Column {
                            ReadingStreakCard(uiState)
                            Spacer(Modifier.height(bottomContentPadding))
                        }
                    }
                }
            }
        }

        StatusGradientOverlay(backdrop = topBlurBackdrop.takeIf { isLiquidGlass })
    }

    detailSession?.let { item ->
        ReadingSessionDetailSheet(
            item = item,
            onDismiss = { detailSession = null },
            onSaveNote = { note, mood -> viewModel.saveSessionNote(item.id, note, mood) }
        )
    }
}

@Composable
internal fun PeriodSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop?
) {
    if (LocalAppTheme.current == "liquid_glass" && !LocalEInkMode.current) {
        LiquidGlassSegmentedControl(
            itemCount = labels.size,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.lg),
            trackHeight = 44.dp,
            trackPadding = 3.dp,
            backdrop = backdrop
        ) { index, isSelected ->
            Text(
                text = labels[index],
                fontSize = AppType.BodySmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) AppColors.TextPrimary.copy(alpha = 0.86f) else AppColors.TextSecondary
            )
        }
        return
    }

    val motionEnabled = LocalMotionEnabled.current
    val shape = AppRoundedCornerShape(AppRadius.md)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.lg)
            .height(44.dp)
            .clip(shape)
            .background(AppColors.BgGray)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / labels.size
        val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = if (motionEnabled) tween(190) else snap(),
            label = "statisticsSegmentIndicator"
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxSize()
                .shadow(3.dp, AppRoundedCornerShape(AppRadius.sm))
                .cardOutline(AppRoundedCornerShape(AppRadius.sm))
                .clip(AppRoundedCornerShape(AppRadius.sm))
                .lumiCardSurface(shape = AppRoundedCornerShape(AppRadius.sm))
        )
        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(AppRoundedCornerShape(AppRadius.sm))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = AppType.BodySmall,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selectedIndex) AppColors.TextPrimary else AppColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReadingSummary(uiState: StatisticsUiState, selectedPeriod: Int) {
    val durations = when (selectedPeriod) {
        0 -> uiState.weeklyData.map { it.duration }
        1 -> uiState.monthlyDailyData.values.toList()
        else -> uiState.yearlyDailyData.values.toList()
    }
    val totalMinutes = (durations.sum() / 1000L / 60L).toInt()
    val activeDays = durations.count { it > 0L }
    val loading = when (selectedPeriod) {
        0 -> uiState.weeklyLoading
        1 -> uiState.monthlyLoading
        else -> uiState.yearlyLoading
    }
    val sessions = uiState.periodSessions
    val dailyTotalMs = durations.sum()
    val weekDates = uiState.weeklyData.map { it.date }.toSet()
    val monthPrefix = String.format(Locale.US, "%04d-%02d-", uiState.displayYear, uiState.displayMonth + 1)
    val yearPrefix = "${uiState.displayYear}-"
    val periodContributedMs = sessions.sumOf { session ->
        session.dailyContributions.entries.sumOf { (date, contributionMs) ->
            val inPeriod = when (selectedPeriod) {
                0 -> date in weekDates
                1 -> date.startsWith(monthPrefix)
                else -> date.startsWith(yearPrefix)
            }
            if (inPeriod) contributionMs else 0L
        }
    }
    // Legacy aggregates and other devices are real time, but they have no local sessions.
    val hasUndocumentedTime = dailyTotalMs - periodContributedMs > 1_000L
    val coverageComplete = !uiState.sessionsLoading && !loading && !hasUndocumentedTime
    val effectiveSessions = sessions.filter { it.isEffective }
    val sessionCount = when {
        uiState.sessionsLoading -> null
        coverageComplete -> effectiveSessions.size
        totalMinutes > 0 -> null
        else -> 0
    }
    val averageMinutes = effectiveSessions
        .takeIf { coverageComplete && it.isNotEmpty() }
        ?.let { it.sumOf { session -> session.duration } / 1000L / 60L / it.size }
    val label = when (selectedPeriod) {
        0 -> stringResource(R.string.statistics_week_reading_label)
        1 -> stringResource(R.string.statistics_month_reading_label)
        else -> stringResource(R.string.statistics_year_reading_label)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpace.lg)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        ReadingDurationHeadline(hours = totalMinutes / 60, minutes = totalMinutes % 60)
        Spacer(Modifier.height(AppSpace.md))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryMetric(
                value = sessionCount?.toString() ?: "—",
                label = stringResource(R.string.statistics_session_count),
                modifier = Modifier.weight(1f)
            )
            MetricDivider()
            SecondaryMetric(
                value = if (loading) "—" else activeDays.toString(),
                label = stringResource(R.string.statistics_active_days),
                modifier = Modifier.weight(1f)
            )
            MetricDivider()
            SecondaryMetric(
                value = averageMinutes?.let { minutes -> formatReadingDurationCompact(minutes * 60_000L) } ?: "—",
                label = stringResource(R.string.statistics_average_session),
                modifier = Modifier.weight(1f)
            )
        }
        if (hasUndocumentedTime && !uiState.sessionsLoading && !loading) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.statistics_legacy_coverage_hint),
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
        }
    }
}

@Composable
private fun ReadingDurationHeadline(hours: Int, minutes: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = hours.toString(),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary
        )
        Text(
            text = stringResource(R.string.unit_hour),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
        )
        Text(
            text = minutes.toString(),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(start = 6.dp)
        )
        Text(
            text = stringResource(R.string.unit_minute),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
        )
    }
}

@Composable
private fun SecondaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
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
private fun MetricDivider() {
    Box(
        Modifier
            .width(0.5.dp)
            .height(28.dp)
            .background(AppColors.Divider)
    )
}

@Composable
private fun PeriodHeader(
    title: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        PeriodNavButton(
            icon = directionalIcon(AppIcons.CaretLeft, AppIcons.CaretRight),
            description = stringResource(R.string.statistics_previous_period),
            enabled = true,
            onClick = onPrevious
        )
        Spacer(Modifier.width(2.dp))
        PeriodNavButton(
            icon = directionalIcon(AppIcons.CaretRight, AppIcons.CaretLeft),
            description = stringResource(R.string.statistics_next_period),
            enabled = nextEnabled,
            onClick = onNext
        )
    }
}

@Composable
private fun PeriodNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(AppRoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun WeeklyOverview(
    uiState: StatisticsUiState,
    animateChart: Boolean = false,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenDay: (String) -> Unit
) {
    val isCurrentWeek = uiState.displayWeekOffset == 0
    val weekData = uiState.weeklyData
    val totalMinutes = (weekData.sumOf { it.duration } / 1000 / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val activeDays = weekData.count { it.duration > 0 }
    var selectedDay by rememberSaveable(uiState.displayWeekOffset) { mutableIntStateOf(-1) }

    val titleText = if (weekData.isNotEmpty()) {
        val startParts = weekData.first().date.split("-")
        val endParts = weekData.last().date.split("-")
        stringResource(
            R.string.month_format,
            startParts[1].toInt(),
            startParts[2].toInt(),
            endParts[1].toInt(),
            endParts[2].toInt()
        )
    } else ""

    val todayIndex = if (isCurrentWeek) Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1 else -1
    val weeklyMinutes = MutableList(7) { 0f }
    weekData.take(7).forEachIndexed { index, daily ->
        weeklyMinutes[index] = (daily.duration / 1000f / 60f)
    }
    if (isCurrentWeek) {
        weeklyMinutes[todayIndex] = maxOf(weeklyMinutes[todayIndex], uiState.todayReadingTime / 1000f / 60f)
    }
    val hasData = weeklyMinutes.any { it > 0f }
    val today = LocalDate.now().toString()

    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(AppSpace.md),
        verticalArrangement = Arrangement.Top
    ) {
        PeriodHeader(
            title = titleText,
            onPrevious = onPrevious,
            onNext = onNext,
            nextEnabled = !isCurrentWeek
        )
        Text(
            text = stringResource(R.string.reading_time_hours, hours, minutes, activeDays),
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(AppSpace.md))

        when {
            uiState.weeklyLoading && weekData.isEmpty() -> LoadingBlock()
            !hasData -> EmptyWeekBlock()
            else -> {
                val labels = listOf(
                    stringResource(R.string.day_sunday),
                    stringResource(R.string.day_monday),
                    stringResource(R.string.day_tuesday),
                    stringResource(R.string.day_wednesday),
                    stringResource(R.string.day_thursday),
                    stringResource(R.string.day_friday),
                    stringResource(R.string.day_saturday)
                )
                val accentColor = AppColors.Accent
                val accentDim = accentColor.copy(alpha = 0.35f)
                val textSecColor = AppColors.TextSecondary
                val maxVal = (weeklyMinutes.maxOrNull() ?: 0f).coerceAtLeast(5f)
                val labelSize = 11.sp
                val chartProgress = remember { Animatable(if (animateChart) 0f else 1f) }
                LaunchedEffect(animateChart) {
                    if (animateChart) chartProgress.animateTo(1f, tween(220))
                }
                val accentArgb = accentColor.toArgbCompat()
                val textSecArgb = textSecColor.toArgbCompat()
                val barShape = AppRoundedCornerShape(4.dp)
                val context = LocalContext.current

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(144.dp)
                        .pointerInput(weekData, selectedDay, today) {
                            detectTapGestures { offset ->
                                if (weekData.size < 7) return@detectTapGestures
                                val slot = size.width / 7f
                                val index = (offset.x / slot).toInt().coerceIn(0, 6)
                                val date = weekData[index].date
                                if (date > today) return@detectTapGestures
                                if (selectedDay == index) onOpenDay(date) else selectedDay = index
                            }
                        }
                ) {
                    val labelHeight = labelSize.toPx() + 6.dp.toPx()
                    val barAreaHeight = size.height - labelHeight
                    val barWidth = size.width / (7 * 2 - 1)

                    weeklyMinutes.forEachIndexed { index, value ->
                        val normalized = (value / maxVal).coerceIn(0f, 1f)
                        val barHeight = barAreaHeight * normalized * chartProgress.value
                        val x = index * barWidth * 2
                        val isToday = index == todayIndex
                        val isSelected = index == selectedDay
                        val visibleHeight = if (value > 0f) maxOf(barHeight, 2.dp.toPx()) else 0f
                        if (visibleHeight > 0f) {
                            drawAppShape(
                                shape = barShape,
                                color = when {
                                    isToday -> accentColor
                                    isSelected -> accentColor.copy(alpha = 0.6f)
                                    else -> accentDim
                                },
                                topLeft = Offset(x, barAreaHeight - visibleHeight),
                                size = Size(barWidth, visibleHeight)
                            )
                        }
                    }

                    val paint = android.graphics.Paint().apply {
                        textSize = labelSize.toPx()
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    labels.forEachIndexed { index, label ->
                        val isToday = index == todayIndex
                        paint.color = if (isToday) accentArgb else textSecArgb
                        paint.isFakeBoldText = isToday
                        val cx = index * barWidth * 2 + barWidth / 2
                        drawContext.canvas.nativeCanvas.drawText(label, cx, size.height - 2.dp.toPx(), paint)
                    }
                }

                if (selectedDay in weekData.indices) {
                    val date = weekData[selectedDay].date
                    val dayMinutes = (weeklyMinutes[selectedDay]).toLong()
                    DayDetailRow(
                        date = date,
                        minutes = dayMinutes,
                        sessionCount = sessionCountForDay(uiState, date),
                        onOpen = { onOpenDay(date) }
                    )
                }
            }
        }
    }
}

private fun sessionCountForDay(uiState: StatisticsUiState, date: String): Int? {
    if (uiState.periodSessions.isEmpty()) return null
    return uiState.periodSessions.count { it.date == date && it.isEffective }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.statistics_loading),
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary
        )
    }
}

@Composable
private fun EmptyWeekBlock() {
    Box(
        modifier = Modifier.fillMaxWidth().height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.statistics_week_empty_title),
                fontSize = AppType.BodySmall,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.statistics_week_empty_hint),
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
        }
    }
}

@Composable
private fun DayDetailRow(
    date: String,
    minutes: Long,
    sessionCount: Int?,
    onOpen: () -> Unit
) {
    val dayLabel = sessionDayLabel(date)
    val duration = formatReadingDuration(minutes * 60_000L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRoundedCornerShape(10.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onOpen() }
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (sessionCount != null) {
                stringResource(R.string.statistics_day_detail, dayLabel, duration, sessionCount)
            } else {
                stringResource(R.string.statistics_day_detail_no_sessions, dayLabel, duration)
            },
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.statistics_view_day),
            fontSize = AppType.Caption,
            fontWeight = FontWeight.Medium,
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

// ─── 月热力图 ──────────────────────────────────────────────────

@Composable
internal fun MonthlyHeatmap(
    uiState: StatisticsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenDay: (String) -> Unit
) {
    val year = uiState.displayYear
    val month = uiState.displayMonth.coerceIn(0, 11)
    val now = Calendar.getInstance()
    val isCurrentMonth = (year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH))
    val today = if (isCurrentMonth) now.get(Calendar.DAY_OF_MONTH) else -1

    val firstDay = Calendar.getInstance().apply { set(year, month, 1) }
    val startDayOfWeek = firstDay.get(Calendar.DAY_OF_WEEK) - 1
    val daysInMonth = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)

    val totalMinutes = (uiState.monthlyReadingTime / 1000 / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val activeDays = uiState.monthlyDailyData.values.count { it > 0L }
    var selectedDay by rememberSaveable(year, month) { mutableStateOf<Int?>(null) }

    val monthNames = listOf(
        stringResource(R.string.month_january),
        stringResource(R.string.month_february),
        stringResource(R.string.month_march),
        stringResource(R.string.month_april),
        stringResource(R.string.month_may),
        stringResource(R.string.month_june),
        stringResource(R.string.month_july),
        stringResource(R.string.month_august),
        stringResource(R.string.month_september),
        stringResource(R.string.month_october),
        stringResource(R.string.month_november),
        stringResource(R.string.month_december)
    )
    val title = stringResource(
        R.string.year_month_format,
        stringResource(R.string.year_format, year),
        monthNames[month]
    )

    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(AppSpace.md),
        verticalArrangement = Arrangement.Top
    ) {
        PeriodHeader(
            title = title,
            onPrevious = onPrevious,
            onNext = onNext,
            nextEnabled = !isCurrentMonth
        )
        Text(
            text = if (uiState.monthlyLoading) {
                stringResource(R.string.statistics_loading)
            } else {
                stringResource(R.string.reading_time_hours, hours, minutes, activeDays)
            },
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(AppSpace.md))

        val weekLabels = listOf(
            stringResource(R.string.day_sunday),
            stringResource(R.string.day_monday),
            stringResource(R.string.day_tuesday),
            stringResource(R.string.day_wednesday),
            stringResource(R.string.day_thursday),
            stringResource(R.string.day_friday),
            stringResource(R.string.day_saturday)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            weekLabels.forEach { label ->
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(AppSpace.xs))

        val totalCells = startDayOfWeek + daysInMonth
        val rows = (totalCells + 6) / 7

        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val dayIndex = row * 7 + col - startDayOfWeek + 1
                    if (dayIndex in 1..daysInMonth) {
                        val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayIndex)
                        val duration = uiState.monthlyDailyData[dateStr] ?: 0L
                        val minutesForDay = (duration / 1000 / 60).toInt()
                        val isToday = dayIndex == today
                        val isFuture = isCurrentMonth && dayIndex > today
                        val isSelected = selectedDay == dayIndex
                        val cellColor = when {
                            isFuture -> AppColors.BgGray.copy(alpha = 0.45f)
                            minutesForDay == 0 -> AppColors.BgGray
                            minutesForDay < 15 -> AppColors.Accent.copy(alpha = 0.2f)
                            minutesForDay < 30 -> AppColors.Accent.copy(alpha = 0.4f)
                            minutesForDay < 60 -> AppColors.Accent.copy(alpha = 0.7f)
                            else -> AppColors.Accent
                        }
                        val dayLabel = stringResource(R.string.reading_history_date, month + 1, dayIndex)
                        val durationLabel = formatReadingDuration(duration)
                        val cellDescription = stringResource(
                            R.string.statistics_day_cell_desc,
                            dayLabel,
                            durationLabel
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(AppRoundedCornerShape(4.dp))
                                .background(cellColor)
                                .then(
                                    when {
                                        isSelected -> Modifier.border(
                                            1.5.dp,
                                            AppColors.TextPrimary,
                                            AppRoundedCornerShape(4.dp)
                                        )
                                        isToday -> Modifier.border(
                                            1.5.dp,
                                            AppColors.Accent,
                                            AppRoundedCornerShape(4.dp)
                                        )
                                        else -> Modifier
                                    }
                                )
                                .clickable(
                                    enabled = !isFuture,
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    selectedDay = if (isSelected) null else dayIndex
                                }
                                .semantics { contentDescription = cellDescription },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$dayIndex",
                                fontSize = 10.sp,
                                color = when {
                                    isFuture -> AppColors.TextSecondary.copy(alpha = 0.35f)
                                    minutesForDay > 0 && minutesForDay >= 30 -> Color.White
                                    else -> AppColors.TextSecondary
                                },
                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        selectedDay?.let { day ->
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
            val duration = uiState.monthlyDailyData[dateStr] ?: 0L
            DayDetailRow(
                date = dateStr,
                minutes = duration / 60_000L,
                sessionCount = sessionCountForDay(uiState, dateStr),
                onOpen = { onOpenDay(dateStr) }
            )
        }
    }
}

// ─── 年热力图（GitHub 贡献图风格，Canvas 渲染）──────────────────

@Composable
internal fun YearlyHeatmap(
    uiState: StatisticsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenDay: (String) -> Unit
) {
    val displayYear = uiState.displayYear
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val isCurrentYear = displayYear == currentYear

    val yearStart = Calendar.getInstance().apply {
        set(displayYear, Calendar.JANUARY, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
    }
    val startCal = yearStart.clone() as Calendar
    while (startCal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) {
        startCal.add(Calendar.DAY_OF_YEAR, -1)
    }

    val endCal = if (isCurrentYear) Calendar.getInstance() else yearStart.clone() as Calendar
    if (!isCurrentYear) endCal.set(displayYear, Calendar.DECEMBER, 31)

    val yearEndStr = String.format(Locale.US, "%04d-12-31", displayYear)
    val todayStr = LocalDate.now().toString()

    val daysBetween = ((endCal.timeInMillis - startCal.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
    val weeks = (daysBetween + 1 + 6) / 7

    val totalMinutes = (uiState.yearlyDailyData.values.sum() / 1000 / 60).toInt()
    val hours = totalMinutes / 60
    val activeDays = uiState.yearlyDailyData.values.count { it > 0L }

    val accentColor = AppColors.Accent
    val monthLabels = (1..12).map { stringResource(R.string.month_label_format, it) }

    val scrollState = rememberScrollState()
    var selectedDate by rememberSaveable(displayYear) { mutableStateOf<String?>(null) }
    val showScrollHint = scrollState.maxValue > 0 && scrollState.value < scrollState.maxValue

    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(AppSpace.md),
        verticalArrangement = Arrangement.Top
    ) {
        PeriodHeader(
            title = stringResource(R.string.year_format, displayYear),
            onPrevious = onPrevious,
            onNext = onNext,
            nextEnabled = !isCurrentYear
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (uiState.yearlyLoading) {
                    stringResource(R.string.statistics_loading)
                } else {
                    stringResource(R.string.reading_time_hours_short, hours, activeDays)
                },
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (showScrollHint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.ArrowsLeftRight,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.statistics_scroll_year_hint),
                        fontSize = 11.sp,
                        color = AppColors.TextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(AppSpace.md))

        val bgGray = AppColors.BgGray
        val c0 = accentColor.copy(alpha = 0.2f)
        val c1 = accentColor.copy(alpha = 0.4f)
        val c2 = accentColor.copy(alpha = 0.7f)
        val c3 = accentColor
        val textSecArgb = AppColors.TextSecondary.toArgbCompat()
        val selectedOutline = AppColors.TextPrimary

        val labelHeight = 16.dp
        val cellSizeDp = 12
        val gapDp = 2
        val gridWidth = (weeks * (cellSizeDp + gapDp)).dp
        val gridHeight = (16 + 7 * 12 + 2).dp
        val cellShape = AppRoundedCornerShape(2.dp)
        val gridStartMillis = remember(displayYear) { startCal.timeInMillis }
        val yearStartStr = String.format(Locale.US, "%04d-01-01", displayYear)
        val heatmapDescription = stringResource(R.string.statistics_year_heatmap_desc, displayYear)

        Box(modifier = Modifier.horizontalScroll(scrollState)) {
            Canvas(
                modifier = Modifier
                    .width(gridWidth)
                    .height(gridHeight)
                    .semantics { contentDescription = heatmapDescription }
                    .pointerInput(displayYear, weeks) {
                        detectTapGestures { offset ->
                            val cellSizePx = cellSizeDp.dp.toPx()
                            val gapPx = gapDp.dp.toPx()
                            val cellStepPx = cellSizePx + gapPx
                            val labelPx = labelHeight.toPx()
                            if (offset.y < labelPx) return@detectTapGestures
                            val week = (offset.x / cellStepPx).toInt()
                            val dayOfWeek = ((offset.y - labelPx) / cellStepPx).toInt()
                            if (week !in 0 until weeks || dayOfWeek !in 0..6) return@detectTapGestures
                            val cellCal = Calendar.getInstance().apply {
                                timeInMillis = gridStartMillis
                                add(Calendar.DAY_OF_YEAR, week * 7 + dayOfWeek)
                            }
                            val cellDate = String.format(
                                Locale.US,
                                "%04d-%02d-%02d",
                                cellCal.get(Calendar.YEAR),
                                cellCal.get(Calendar.MONTH) + 1,
                                cellCal.get(Calendar.DAY_OF_MONTH)
                            )
                            if (cellDate < yearStartStr || cellDate > yearEndStr) return@detectTapGestures
                            if (isCurrentYear && cellDate > todayStr) return@detectTapGestures
                            selectedDate = if (selectedDate == cellDate) null else cellDate
                        }
                    }
            ) {
                val cellSizePx = cellSizeDp.dp.toPx()
                val gapPx = gapDp.dp.toPx()
                val cellStepPx = cellSizePx + gapPx

                var lastMonth = -1
                for (week in 0 until weeks) {
                    val weekCal = (startCal.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, week) }
                    val month = weekCal.get(Calendar.MONTH)
                    val dayOfMonth = weekCal.get(Calendar.DAY_OF_MONTH)
                    if (month != lastMonth && dayOfMonth <= 7) {
                        val paint = android.graphics.Paint().apply {
                            color = textSecArgb
                            textSize = 11.sp.toPx()
                            isAntiAlias = true
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            monthLabels[month.coerceIn(0, 11)],
                            week * cellStepPx,
                            labelHeight.toPx() - 3.dp.toPx(),
                            paint
                        )
                        lastMonth = month
                    }
                }

                val gridTop = labelHeight.toPx()
                for (week in 0 until weeks) {
                    for (dayOfWeek in 0..6) {
                        val cellCal = (startCal.clone() as Calendar).apply {
                            add(Calendar.WEEK_OF_YEAR, week)
                            add(Calendar.DAY_OF_WEEK, dayOfWeek)
                        }
                        val cellDateStr = String.format(
                            Locale.US,
                            "%04d-%02d-%02d",
                            cellCal.get(Calendar.YEAR),
                            cellCal.get(Calendar.MONTH) + 1,
                            cellCal.get(Calendar.DAY_OF_MONTH)
                        )
                        val isInYear = cellDateStr >= yearStartStr && cellDateStr <= yearEndStr
                        val isFuture = isCurrentYear && cellDateStr > todayStr
                        val color = when {
                            !isInYear -> bgGray
                            isFuture -> bgGray
                            else -> {
                                val duration = uiState.yearlyDailyData[cellDateStr] ?: 0L
                                val m = (duration / 1000 / 60).toInt()
                                when {
                                    m == 0 -> bgGray
                                    m < 15 -> c0
                                    m < 30 -> c1
                                    m < 60 -> c2
                                    else -> c3
                                }
                            }
                        }
                        val topLeft = Offset(week * cellStepPx, gridTop + dayOfWeek * cellStepPx)
                        drawAppShape(
                            shape = cellShape,
                            color = color,
                            topLeft = topLeft,
                            size = Size(cellSizePx, cellSizePx)
                        )
                        if (cellDateStr == selectedDate) {
                            drawRoundRect(
                                color = selectedOutline,
                                topLeft = topLeft,
                                size = Size(cellSizePx, cellSizePx),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpace.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.heat_low), fontSize = 11.sp, color = AppColors.TextSecondary)
            Spacer(Modifier.width(4.dp))
            listOf(
                AppColors.BgGray,
                accentColor.copy(alpha = 0.2f),
                accentColor.copy(alpha = 0.4f),
                accentColor.copy(alpha = 0.7f),
                accentColor
            ).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .padding(1.dp)
                        .clip(AppRoundedCornerShape(2.dp))
                        .background(color)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.heat_high), fontSize = 11.sp, color = AppColors.TextSecondary)
        }

        selectedDate?.let { date ->
            DayDetailRow(
                date = date,
                minutes = (uiState.yearlyDailyData[date] ?: 0L) / 60_000L,
                sessionCount = sessionCountForDay(uiState, date),
                onOpen = { onOpenDay(date) }
            )
        }
    }
}

// ─── 最近阅读 ──────────────────────────────────────────────────

@Composable
private fun RecentReadingSection(
    sessions: List<ReadingSessionItem>,
    loading: Boolean,
    onOpenAll: () -> Unit,
    onOpenSession: (ReadingSessionItem) -> Unit
) {
    val effectiveSessions = sessions.filter { it.isEffective }
    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpace.md, end = AppSpace.sm, top = AppSpace.md, bottom = AppSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.recent_reading),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            ViewAllAction(onClick = onOpenAll)
        }

        when {
            loading && effectiveSessions.isEmpty() -> {
                Text(
                    text = stringResource(R.string.statistics_loading),
                    fontSize = AppType.BodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = AppSpace.md, vertical = AppSpace.md)
                )
            }
            effectiveSessions.isEmpty() -> {
                Text(
                    text = stringResource(R.string.recent_reading_empty),
                    fontSize = AppType.BodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = AppSpace.md).padding(bottom = AppSpace.md)
                )
            }
            else -> {
                effectiveSessions.take(3).forEachIndexed { index, item ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 68.dp, end = AppSpace.md)
                                .height(0.5.dp)
                                .background(AppColors.Divider)
                        )
                    }
                    RecentReadingRow(item = item, onClick = { onOpenSession(item) })
                }
                Spacer(Modifier.height(AppSpace.xs))
            }
        }
    }
}

@Composable
private fun RecentReadingRow(item: ReadingSessionItem, onClick: () -> Unit) {
    val delta = item.progressDeltaPercent()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressEffect()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = AppSpace.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.coverPath,
            contentDescription = item.bookTitle,
            modifier = Modifier
                .size(40.dp, 56.dp)
                .clip(AppRoundedCornerShape(4.dp))
                .background(AppColors.BgGray),
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
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${sessionRecentTimeLabel(item.startTime)} · ${formatReadingDuration(item.duration)}",
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (delta != null && delta > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.recent_reading_progress,
                        (item.startProgress * 100f).toInt().coerceIn(0, 100),
                        (item.endProgress * 100f).toInt().coerceIn(0, 100)
                    ),
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary,
                    maxLines = 1
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
private fun ViewAllAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(AppRoundedCornerShape(8.dp))
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
            text = stringResource(R.string.recent_reading_all),
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

// ─── 最常阅读 ──────────────────────────────────────────────────

@Composable
private fun MostReadBooks(books: List<MostReadBook>, onOpen: (String) -> Unit) {
    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.most_read_books),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .padding(horizontal = AppSpace.md, vertical = AppSpace.md)
                .semantics { heading() }
        )

        if (books.isEmpty()) {
            Text(
                text = stringResource(R.string.no_reading_records),
                fontSize = AppType.BodySmall,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(horizontal = AppSpace.md).padding(bottom = AppSpace.md)
            )
        } else {
            books.forEachIndexed { index, book ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 68.dp, end = AppSpace.md)
                            .height(0.5.dp)
                            .background(AppColors.Divider)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressEffect()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Button
                        ) { onOpen(book.bookId) }
                        .padding(horizontal = AppSpace.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = book.coverPath,
                        contentDescription = book.title,
                        modifier = Modifier
                            .size(40.dp, 53.dp)
                            .clip(AppRoundedCornerShape(4.dp))
                            .background(AppColors.BgGray),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(AppSpace.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = book.author,
                            fontSize = AppType.Caption,
                            color = AppColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = formatReadingDuration(book.totalDuration),
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(AppSpace.xs))
                    Icon(
                        imageVector = directionalIcon(AppIcons.CaretRight, AppIcons.CaretLeft),
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(Modifier.height(AppSpace.xs))
        }
    }
}

// ─── 连续阅读 ──────────────────────────────────────────────────

@Composable
private fun ReadingStreakCard(uiState: StatisticsUiState) {
    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(AppSpace.md),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.statistics_streak_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(AppSpace.md))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.xl)) {
            StreakValue(
                value = stringResource(R.string.streak_days, uiState.currentStreak),
                label = stringResource(R.string.statistics_streak_current)
            )
            StreakValue(
                value = stringResource(R.string.streak_days, uiState.longestStreak),
                label = stringResource(R.string.statistics_streak_longest)
            )
        }
        Spacer(Modifier.height(AppSpace.sm))
        Text(
            text = if (uiState.dailyGoal > 0) {
                stringResource(R.string.statistics_streak_goal_hint, uiState.dailyGoal)
            } else {
                stringResource(R.string.statistics_streak_any_hint)
            },
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary
        )
    }
}

@Composable
private fun StreakValue(value: String, label: String) {
    Column {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = AppType.Caption, color = AppColors.TextSecondary)
    }
}

// ─── 高亮与笔记 ────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationsOverview(summary: AnnotationPeriodSummary, onOpen: () -> Unit) {
    InformationCard(
        modifier = Modifier.padding(horizontal = AppSpace.lg),
        contentPadding = PaddingValues(AppSpace.md),
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppRoundedCornerShape(8.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    role = Role.Button,
                    onClick = onOpen
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.statistics_annotations_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            Text(
                text = stringResource(R.string.statistics_annotations_view_all),
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
        Spacer(Modifier.height(AppSpace.md))
        Row(modifier = Modifier.fillMaxWidth()) {
            AnnotationCount(summary.highlights, stringResource(R.string.tab_highlights), Modifier.weight(1f))
            AnnotationCount(summary.underlines, stringResource(R.string.tab_underline), Modifier.weight(1f))
            AnnotationCount(summary.notes, stringResource(R.string.tab_notes), Modifier.weight(1f))
            AnnotationCount(summary.bookmarks, stringResource(R.string.tab_bookmarks), Modifier.weight(1f))
        }
        if (summary.topTags.isNotEmpty()) {
            Spacer(Modifier.height(AppSpace.md))
            Text(
                text = stringResource(R.string.statistics_top_tags),
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
            Spacer(Modifier.height(AppSpace.xs))
            val chipAlpha = if (LocalAppTheme.current == "liquid_glass") 0.14f else 0.08f
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                summary.topTags.forEach { (tag, count) ->
                    Text(
                        text = stringResource(R.string.statistics_tag_count, tag, count),
                        fontSize = AppType.Caption,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .clip(AppRoundedCornerShape(12.dp))
                            .background(AppColors.Accent.copy(alpha = chipAlpha))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnotationCount(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "$value",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            maxLines = 1
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

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
