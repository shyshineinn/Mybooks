package com.huangder.lumibooks.ui.bookshelf

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.components.LiquidGlassSegmentedControl
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalEInkMode
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class BookDetailsPage(@StringRes val titleRes: Int) {
    Information(R.string.book_detail_tab_info),
    Reading(R.string.book_detail_tab_reading),
    Files(R.string.book_detail_tab_files)
}

/**
 * All themes share one pager and one saved list state per page. Only the tab material changes.
 * Keep this bounded by the screen's remaining height, never inside an unbounded LazyColumn.
 */
@Composable
internal fun BookDetailsPager(
    bookId: String,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = AppSpace.md,
    content: LazyListScope.(BookDetailsPage) -> Unit
) {
    key(bookId) {
        val pages = BookDetailsPage.entries
        val pagerState = rememberPagerState { pages.size }
        val listStates = pages.map { page -> key(page) { rememberLazyListState() } }
        val pageStateHolder = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        val motionEnabled = LocalMotionEnabled.current
        val eInk = LocalEInkMode.current
        val dragging by pagerState.interactionSource.collectIsDraggedAsState()
        var selectionJob by remember { mutableStateOf<Job?>(null) }
        var requestedTab by remember { mutableStateOf<Int?>(null) }
        var selectionGeneration by remember { mutableIntStateOf(0) }

        LaunchedEffect(dragging) {
            if (dragging) {
                selectionGeneration++
                selectionJob?.cancel()
                requestedTab = null
            }
        }
        val selectTab: (Int) -> Unit = { index ->
            val generation = ++selectionGeneration
            selectionJob?.cancel()
            requestedTab = index
            selectionJob = scope.launch {
                try {
                    if (motionEnabled && !eInk) pagerState.animateScrollToPage(index)
                    else pagerState.scrollToPage(index)
                } finally {
                    // A cancelled selection must not clear a newer request.
                    if (selectionGeneration == generation) requestedTab = null
                }
            }
        }

        Column(modifier) {
            BookDetailsTabBar(
                selectedIndex = requestedTab ?: pagerState.currentPage,
                position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                // A tab gesture owns its spring; a content swipe owns the continuous position.
                glassPosition = {
                    if (requestedTab == null) {
                        pagerState.currentPage + pagerState.currentPageOffsetFraction
                    } else null
                },
                onSelected = selectTab,
                modifier = Modifier.padding(horizontal = AppSpace.lg, vertical = AppSpace.sm)
            )
            HorizontalPager(
                state = pagerState,
                key = { pages[it].name },
                userScrollEnabled = motionEnabled && !eInk,
                modifier = Modifier.weight(1f).testTag("book_details_pager")
            ) { index ->
                val page = pages[index]
                pageStateHolder.SaveableStateProvider(page.name) {
                    LazyColumn(
                        state = listStates[index],
                        modifier = Modifier.fillMaxSize().navigationBarsPadding()
                            .testTag("book_details_page_${page.name}"),
                        contentPadding = PaddingValues(
                            start = AppSpace.md, end = AppSpace.md,
                            top = AppSpace.sm, bottom = bottomContentPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpace.md)
                    ) {
                        content(page)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookDetailsTabBar(
    selectedIndex: Int,
    position: () -> Float,
    glassPosition: () -> Float?,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pages = BookDetailsPage.entries
    val labels = pages.map { stringResource(it.titleRes) }
    val eInk = LocalEInkMode.current
    // Match the annotation filters: 40dp normally, scaling to at most 64dp.
    val height = segmentedTabHeight(LocalDensity.current.fontScale)
    val theme = LocalAppTheme.current

    if (theme == "liquid_glass" && !eInk) {
        LiquidGlassSegmentedControl(
            itemCount = pages.size,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            trackHeight = height,
            trackPadding = 2.dp,
            externalPosition = glassPosition,
            modifier = modifier.fillMaxWidth().testTag("book_details_tabs_glass")
        ) { index, selected ->
            DetailTabLabel(labels[index], selected)
        }
    } else if (theme == "material3" && !eInk) {
        TabRow(
            selectedTabIndex = selectedIndex,
            modifier = modifier.fillMaxWidth().testTag("book_details_tabs_material3"),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabs ->
                val value = position().coerceIn(0f, pages.lastIndex.toFloat())
                val lower = value.toInt()
                val upper = (lower + 1).coerceAtMost(pages.lastIndex)
                val fraction = value - lower
                val left = tabs[lower].left + (tabs[upper].left - tabs[lower].left) * fraction
                val width = tabs[lower].width + (tabs[upper].width - tabs[lower].width) * fraction
                Box(
                    Modifier.fillMaxWidth().wrapContentSize(Alignment.BottomStart)
                        .offset(x = left).width(width).height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        ) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                    modifier = Modifier.height(height).testTag("book_details_tab_$index"),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    DetailTabLabel(label, selectedIndex == index, color = Color.Unspecified)
                }
            }
        }
    } else {
        val shape = AppRoundedCornerShape(if (eInk) 8.dp else 20.dp)
        val indicatorShape = AppRoundedCornerShape(if (eInk) 6.dp else 18.dp)
        BoxWithConstraints(
            modifier.fillMaxWidth().height(height)
                .testTag(if (eInk) "book_details_tabs_eink" else "book_details_tabs_lumi")
                .clip(shape)
                .background(if (eInk) Color.White else AppColors.BgGray)
                .then(if (eInk) Modifier.border(1.dp, Color.Black, shape) else Modifier)
                .padding(2.dp)
                .selectableGroup()
        ) {
            val segmentWidth = maxWidth / pages.size
            Box(
                Modifier.offset(x = segmentWidth * position().coerceIn(0f, pages.lastIndex.toFloat()))
                    .width(segmentWidth).fillMaxHeight()
                    .shadow(if (eInk) 0.dp else 2.dp, indicatorShape)
                    .background(if (eInk) Color.Black else AppColors.CardBg, indicatorShape)
            )
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { index, label ->
                    val selected = selectedIndex == index
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .testTag("book_details_tab_$index")
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelected(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        DetailTabLabel(
                            label, selected,
                            color = when {
                                eInk && selected -> Color.White
                                eInk -> Color.Black
                                selected -> AppColors.TextPrimary
                                else -> AppColors.TextSecondary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTabLabel(
    label: String,
    selected: Boolean,
    color: Color = if (selected) AppColors.TextPrimary.copy(alpha = 0.86f) else AppColors.TextSecondary
) {
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = 8.dp),
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
