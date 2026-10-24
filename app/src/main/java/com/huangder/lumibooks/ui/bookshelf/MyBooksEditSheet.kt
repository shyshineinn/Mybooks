package com.huangder.lumibooks.ui.bookshelf

import android.widget.Toast
import com.huangder.lumibooks.ui.components.ReplacingToast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksCreatedBook
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.ui.components.LiquidGlassAlertDialog
import com.huangder.lumibooks.ui.components.LiquidGlassBottomSheetScaffold
import com.huangder.lumibooks.ui.components.LiquidGlassIconButton
import com.huangder.lumibooks.ui.components.LiquidGlassSegmentedControl
import com.huangder.lumibooks.ui.components.LiquidGlassSurface
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.components.PillSlider
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import java.net.URLEncoder
import java.util.Locale

private val ErrorColor = Color(0xFFC62828)
private val DestructiveColor = Color(0xFFD92D3A)

@Composable
fun MyBooksEditSheet(
    book: Book?,
    meta: ServerBookMeta?,
    onDismiss: (MyBooksCreatedBook?) -> Unit,
    createSource: MyBooksCreateSource? = null,
    viewModel: MyBooksEditViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val toast = remember(context) { ReplacingToast(context) }
    var discardOpen by remember { mutableStateOf(false) }
    var saved = false
    val createMode = createSource != null
    LaunchedEffect(createSource?.uri, book?.id) {
        if (createSource != null) {
            viewModel.beginCreate(createSource)
        } else if (book != null) {
            viewModel.begin(book, meta)
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.dismiss() } }

    // 外壳与书架的标签弹窗共用：对话框宿主层（浮在标签栏之上）+ 底部对齐的抽屉动效。
    LiquidGlassBottomSheetScaffold(
        // 同样要读实时值：新建成功后立刻关闭时，collectAsState 的 state 还是旧的，
        // created 会是 null，本地副本就建立不起来。
        onDismiss = { onDismiss(viewModel.uiState.value.created) },
        maxHeightFraction = 0.62f,
        onRequestDismiss = {
            // 必须读 StateFlow 的实时值：collectAsState 的 state 要等下一次重组才更新，
            // 保存刚成功那一刻读到的还是旧的 saving=true，关闭会被拦掉，弹窗就关不掉。
            val live = viewModel.uiState.value
            when {
                // 保存中不响应关闭，避免刚写完就被当成有未保存修改。
                live.saving -> false
                live.dirty -> {
                    discardOpen = true
                    false
                }
                else -> true
            }
        }
    ) { requestDismiss ->
        if (discardOpen) {
            // 放弃确认内联在 Sheet 里：对话框宿主同一时刻只显示一个浮层，
            // 嵌套 AlertDialog 会把整个 Sheet 顶掉再放回来。
            MyBooksDiscardConfirm(
                onKeep = { discardOpen = false },
                onDiscard = { onDismiss(null) }
            )
        } else {
            EditSheetContent(
                state = state,
                createMode = createMode,
                onClose = { requestDismiss() },
                onCancel = { requestDismiss() },
                onSave = {
                    viewModel.save { message ->
                        if (message != null) {
                            saved = true
                            toast.show(message)
                            requestDismiss()
                        }
                    }
                },
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun MyBooksDiscardConfirm(onKeep: () -> Unit, onDiscard: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.mybooks_edit_discard_title),
            fontSize = AppType.Section,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary
        )
        Spacer(Modifier.height(AppSpace.sm))
        Text(
            text = stringResource(R.string.mybooks_edit_discard_message),
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(AppSpace.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
            LiquidGlassTextButton(
                text = stringResource(R.string.mybooks_edit_continue),
                contentColor = AppColors.TextSecondary,
                onClick = onKeep
            )
            LiquidGlassTextButton(
                text = stringResource(R.string.mybooks_edit_discard),
                tintedColor = DestructiveColor,
                onClick = onDiscard
            )
        }
    }
}

@Composable
private fun ColumnScope.EditSheetContent(
    state: MyBooksEditUiState,
    createMode: Boolean,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    viewModel: MyBooksEditViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                if (createMode) R.string.mybooks_create_title else R.string.mybooks_edit_title
            ),
            fontSize = AppType.Section,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        LiquidGlassIconButton(
            imageVector = AppIcons.X,
            contentDescription = stringResource(R.string.close),
            onClick = onClose,
            size = 44.dp,
            iconSize = 20.dp,
            normalContainerColor = AppColors.BgGray
        )
    }

    Spacer(Modifier.height(AppSpace.md))

    if (state.loading || !state.loaded) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpace.lg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AppColors.Accent,
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(R.string.mybooks_edit_loading),
                    fontSize = AppType.BodySmall,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = AppSpace.sm)
                )
            }
        }
        state.error?.let { error ->
            Text(
                text = error,
                fontSize = AppType.Caption,
                color = errorColor(),
                modifier = Modifier
                    .padding(bottom = AppSpace.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    } else {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            BasicFieldsSection(state, viewModel)
            if (state.identifiers.isNotEmpty() || state.loaded) {
                Spacer(Modifier.height(AppSpace.lg))
                IdentifiersSection(state, viewModel)
            }
            if (state.customColumns.isNotEmpty()) {
                Spacer(Modifier.height(AppSpace.lg))
                CustomFieldsSection(state, viewModel)
            }
            Spacer(Modifier.height(AppSpace.lg))
            ReadingLogsSection(state, viewModel)
        }

        state.error?.let { error ->
            Text(
                text = error,
                fontSize = AppType.Caption,
                color = errorColor(),
                modifier = Modifier
                    .padding(top = AppSpace.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }

    Spacer(Modifier.height(AppSpace.md))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiquidGlassTextButton(
            text = stringResource(R.string.cancel),
            enabled = !state.saving,
            contentColor = AppColors.TextSecondary,
            onClick = onCancel,
            modifier = Modifier.height(44.dp)
        )
        Spacer(Modifier.width(AppSpace.sm))
        LiquidGlassTextButton(
            text = if (state.saving) {
                stringResource(
                    if (createMode) R.string.mybooks_create_saving else R.string.mybooks_edit_saving
                )
            } else {
                stringResource(if (createMode) R.string.mybooks_create_action else R.string.save)
            },
            // 新建模式下草稿本身就是新内容，不看 dirty；标题为空时由保存校验拦下。
            enabled = (state.dirty || createMode) && !state.saving,
            tintedColor = AppColors.Accent,
            onClick = onSave,
            modifier = Modifier.height(44.dp)
        )
    }
}

@Composable
private fun BasicFieldsSection(state: MyBooksEditUiState, viewModel: MyBooksEditViewModel) {
    SectionHeader(stringResource(R.string.mybooks_edit_section_basic))

    EditorTextField(
        value = state.title,
        onValueChange = viewModel::updateTitle,
        label = stringResource(R.string.mybooks_edit_field_title),
        error = state.fieldErrors["title"],
        enabled = !state.saving
    )
    Spacer(Modifier.height(AppSpace.md))
    EditorTextField(
        value = state.author,
        onValueChange = viewModel::updateAuthor,
        label = stringResource(R.string.mybooks_edit_field_author),
        enabled = !state.saving
    )
    Spacer(Modifier.height(AppSpace.md))

    Text(
        text = stringResource(R.string.mybooks_edit_field_status),
        fontSize = AppType.Caption,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(bottom = AppSpace.sm)
    )
    StatusSegment(
        selected = when (state.status) {
            "reading" -> 1
            "done" -> 2
            else -> 0
        },
        enabled = !state.saving,
        onSelected = { index ->
            viewModel.updateStatus(when (index) {
                1 -> "reading"
                2 -> "done"
                else -> "wish"
            })
        }
    )
    Spacer(Modifier.height(AppSpace.md))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.mybooks_edit_field_rating),
            fontSize = AppType.Caption,
            fontWeight = FontWeight.Medium,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (state.rating <= 0f) {
                stringResource(R.string.mybooks_edit_not_rated)
            } else {
                stringResource(R.string.mybooks_edit_rating_value, String.format(Locale.US, "%.1f", state.rating))
            },
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary
        )
    }
    Spacer(Modifier.height(AppSpace.sm))
    Box(Modifier.alpha(if (state.saving) 0.6f else 1f)) {
        PillSlider(
            value = state.rating,
            onValueChange = { if (!state.saving) viewModel.updateRating(it) },
            valueRange = 0f..10f,
            step = 0.1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpace.xs)
        )
    }
    Spacer(Modifier.height(AppSpace.md))

    ResponsiveFieldPair(
        first = { modifier ->
            EditorTextField(
                value = state.series,
                onValueChange = viewModel::updateSeries,
                label = stringResource(R.string.mybooks_edit_field_series),
                enabled = !state.saving,
                modifier = modifier
            )
        },
        second = { modifier ->
            EditorTextField(
                value = state.seriesIndex,
                onValueChange = viewModel::updateSeriesIndex,
                label = stringResource(R.string.mybooks_edit_field_series_index),
                error = state.fieldErrors["series_index"],
                keyboardType = KeyboardType.Number,
                enabled = !state.saving,
                modifier = modifier
            )
        }
    )
    Spacer(Modifier.height(AppSpace.md))
    ResponsiveFieldPair(
        first = { modifier ->
            EditorTextField(
                value = state.publisher,
                onValueChange = viewModel::updatePublisher,
                label = stringResource(R.string.mybooks_edit_field_publisher),
                enabled = !state.saving,
                modifier = modifier
            )
        },
        second = { modifier ->
            EditorTextField(
                value = state.pubYear,
                onValueChange = viewModel::updatePubYear,
                label = stringResource(R.string.mybooks_edit_field_pub_year),
                error = state.fieldErrors["pub_year"],
                enabled = !state.saving,
                modifier = modifier
            )
        }
    )
    Spacer(Modifier.height(AppSpace.md))
    ResponsiveFieldPair(
        first = { modifier ->
            EditorTextField(
                value = state.isbn,
                onValueChange = viewModel::updateIsbn,
                label = stringResource(R.string.mybooks_edit_field_isbn),
                error = state.fieldErrors["isbn"],
                keyboardType = KeyboardType.Ascii,
                enabled = !state.saving,
                modifier = modifier
            )
        },
        second = { modifier ->
            EditorTextField(
                value = state.doubanId,
                onValueChange = viewModel::updateDoubanId,
                label = stringResource(R.string.mybooks_edit_field_douban),
                error = state.fieldErrors["douban_id"],
                keyboardType = KeyboardType.Ascii,
                enabled = !state.saving,
                modifier = modifier
            )
        }
    )
    Spacer(Modifier.height(AppSpace.md))

    DoubanFillSection(state, viewModel)

    Spacer(Modifier.height(AppSpace.md))
    EditorTextField(
        value = state.summary,
        onValueChange = viewModel::updateSummary,
        label = stringResource(R.string.mybooks_edit_field_summary),
        error = state.fieldErrors["summary"],
        singleLine = false,
        minHeight = 96.dp,
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done,
        enabled = !state.saving
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoubanFillSection(state: MyBooksEditUiState, viewModel: MyBooksEditViewModel) {
    val context = LocalContext.current
    val toast = remember(context) { ReplacingToast(context) }
    var lastLookupMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.lookupMessage) {
        val message = state.lookupMessage ?: return@LaunchedEffect
        if (message != lastLookupMessage) {
            lastLookupMessage = message
            toast.show(message)
        }
    }
    val canFill = state.title.isNotBlank() || state.isbn.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            // 只在按钮不可用时说明原因；常驻说明文案一律不留。
            if (!canFill) {
                Text(
                    text = stringResource(R.string.mybooks_douban_need_title_isbn),
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
        Spacer(Modifier.width(AppSpace.sm))
        LiquidGlassTextButton(
            text = if (state.lookupRunning) {
                stringResource(R.string.mybooks_douban_filling)
            } else {
                stringResource(R.string.mybooks_douban_fill)
            },
            enabled = canFill && !state.lookupRunning && !state.saving,
            contentColor = AppColors.TextSecondary,
            modifier = Modifier.height(44.dp),
            onClick = { viewModel.lookupDouban() }
        )
    }
    if (state.lookupRunning) {
        Row(
            modifier = Modifier.padding(top = AppSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = AppColors.Accent,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(AppSpace.sm))
            Text(
                text = stringResource(R.string.mybooks_douban_filling),
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
        }
    }
    state.lookupMessage?.let { message ->
        Text(
            text = message,
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpace.sm)
        )
    }

    if (state.candidates.isNotEmpty()) {
        Spacer(Modifier.height(AppSpace.md))
        Text(
            text = stringResource(R.string.mybooks_douban_candidates),
            fontSize = AppType.Caption,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(AppSpace.sm))
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
            val context = LocalContext.current
            state.candidates.forEach { candidate ->
                DoubanCandidateRow(candidate) {
                    viewModel.applyCandidate(candidate)
                    toast.show(context.getString(R.string.mybooks_douban_filled))
                }
            }
        }
    }

    val filledLabel = stringResource(R.string.mybooks_douban_filled)
    if (!state.lookupRunning && state.candidates.isEmpty() &&
        state.lookupMessage != null && state.lookupMessage != filledLabel &&
        state.title.isNotBlank()
    ) {
        Spacer(Modifier.height(AppSpace.xs))
        LiquidGlassTextButton(
            text = stringResource(R.string.mybooks_douban_search_title),
            enabled = !state.saving,
            contentColor = AppColors.TextSecondary,
            onClick = { viewModel.searchByTitle() },
            modifier = Modifier.height(44.dp)
        )
    }
}

@Composable
private fun DoubanCandidateRow(
    candidate: MyBooksDoubanCandidate,
    onClick: () -> Unit
) {
    val coverUrl = candidate.coverUrl?.let { url ->
        MyBooksConfig.SERVER_URL + "/api/img-proxy?url=" + URLEncoder.encode(url, "UTF-8")
    }
    LiquidGlassSurface(
        shape = AppRoundedCornerShape(AppRadius.md),
        fallbackColor = AppColors.BgGray,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        controlEdge = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp, 56.dp)
                    .clip(AppRoundedCornerShape(6.dp))
                    .background(AppColors.BgGray),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Text(
                        text = candidate.title.take(2),
                        fontSize = 10.sp,
                        color = AppColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    fontSize = AppType.BodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val details = listOfNotNull(
                    candidate.author.takeIf { it.isNotBlank() },
                    candidate.publisher.takeIf { it.isNotBlank() },
                    candidate.pubYear.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val extra = listOfNotNull(
                    if (candidate.rating > 0) String.format(Locale.US, "%.1f/10", candidate.rating) else null,
                    candidate.isbn.takeIf { it.isNotBlank() }?.let { "ISBN $it" }
                ).joinToString(" · ")
                if (extra.isNotBlank()) {
                    Text(
                        text = extra,
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentifiersSection(state: MyBooksEditUiState, viewModel: MyBooksEditViewModel) {
    SectionHeader(stringResource(R.string.mybooks_edit_section_identifiers))
    if (state.identifiers.isEmpty()) {
        Text(
            text = stringResource(R.string.mybooks_edit_no_identifiers),
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(vertical = AppSpace.xs)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
            state.identifiers.forEachIndexed { index, row ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stack = maxWidth < 360.dp
                    val error = state.fieldErrors["identifier.$index"]
                    if (stack) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                            EditorTextField(
                                value = row.type,
                                onValueChange = { value ->
                                    viewModel.updateIdentifier(index, type = value.lowercase(Locale.ROOT))
                                },
                                placeholder = stringResource(R.string.mybooks_edit_identifier_type_placeholder),
                                enabled = !state.saving,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EditorTextField(
                                    value = row.value,
                                    onValueChange = { viewModel.updateIdentifier(index, value = it) },
                                    placeholder = stringResource(R.string.mybooks_edit_identifier_value_placeholder),
                                    enabled = !state.saving,
                                    modifier = Modifier.weight(1f)
                                )
                                DeleteIcon(enabled = !state.saving) { viewModel.removeIdentifier(index) }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EditorTextField(
                                value = row.type,
                                onValueChange = { value ->
                                    viewModel.updateIdentifier(index, type = value.lowercase(Locale.ROOT))
                                },
                                placeholder = stringResource(R.string.mybooks_edit_identifier_type_placeholder),
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(AppSpace.sm))
                            EditorTextField(
                                value = row.value,
                                onValueChange = { viewModel.updateIdentifier(index, value = it) },
                                placeholder = stringResource(R.string.mybooks_edit_identifier_value_placeholder),
                                enabled = !state.saving,
                                modifier = Modifier.weight(1.6f)
                            )
                            DeleteIcon(enabled = !state.saving) { viewModel.removeIdentifier(index) }
                        }
                    }
                    if (error != null) {
                        Text(
                            text = error,
                            fontSize = AppType.Caption,
                            color = errorColor(),
                            modifier = Modifier.padding(start = AppSpace.xs, top = AppSpace.xs)
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(AppSpace.xs))
    LiquidGlassTextButton(
        text = stringResource(R.string.mybooks_edit_add_identifier),
        enabled = !state.saving,
        contentColor = AppColors.TextSecondary,
        onClick = { viewModel.addIdentifier() },
        modifier = Modifier.height(44.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomFieldsSection(state: MyBooksEditUiState, viewModel: MyBooksEditViewModel) {
    SectionHeader(stringResource(R.string.mybooks_edit_section_custom))
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
        state.customColumns.forEach { column ->
            val value = state.customValues[column.label].orEmpty()
            val error = state.fieldErrors[com.huangder.lumibooks.data.sync.MyBooksConfig.customOverrideKey(column.label)]
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = column.name,
                    fontSize = AppType.Caption,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(bottom = AppSpace.sm)
                )
                when (column.datatype) {
                    "bool" -> {
                        val index = when (value) {
                            "1" -> 1
                            "0" -> 2
                            else -> 0
                        }
                        TwoOrThreeSegment(
                            labels = listOf(
                                stringResource(R.string.mybooks_edit_custom_unset),
                                stringResource(R.string.mybooks_edit_custom_yes),
                                stringResource(R.string.mybooks_edit_custom_no)
                            ),
                            selected = index,
                            enabled = !state.saving
                        ) { selected ->
                            viewModel.updateCustom(
                                column.label,
                                when (selected) {
                                    1 -> "1"
                                    2 -> "0"
                                    else -> ""
                                }
                            )
                        }
                    }
                    "enumeration" -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AppSpace.sm),
                            verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
                        ) {
                            SelectionChip(
                                text = stringResource(R.string.mybooks_edit_custom_unset),
                                selected = value.isBlank(),
                                enabled = !state.saving
                            ) { viewModel.updateCustom(column.label, "") }
                            column.options.forEach { option ->
                                SelectionChip(
                                    text = option,
                                    selected = value == option,
                                    enabled = !state.saving
                                ) { viewModel.updateCustom(column.label, option) }
                            }
                        }
                    }
                    "rating" -> {
                        val rating = value.toFloatOrNull() ?: 0f
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).alpha(if (state.saving) 0.6f else 1f)) {
                                PillSlider(
                                    value = rating,
                                    onValueChange = {
                                        if (!state.saving) {
                                            viewModel.updateCustom(column.label, String.format(Locale.US, "%.1f", it))
                                        }
                                    },
                                    valueRange = 0f..10f,
                                    step = 0.1f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.width(AppSpace.sm))
                            Text(
                                text = if (rating <= 0f) {
                                    stringResource(R.string.mybooks_edit_not_rated)
                                } else {
                                    stringResource(R.string.mybooks_edit_rating_value, String.format(Locale.US, "%.1f", rating))
                                },
                                fontSize = AppType.Caption,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                    "comments" -> EditorTextField(
                        value = value,
                        onValueChange = { viewModel.updateCustom(column.label, it) },
                        singleLine = false,
                        minHeight = 96.dp,
                        enabled = !state.saving
                    )
                    else -> EditorTextField(
                        value = value,
                        onValueChange = { viewModel.updateCustom(column.label, it) },
                        keyboardType = when (column.datatype) {
                            "int" -> KeyboardType.Number
                            "float" -> KeyboardType.Decimal
                            "date" -> KeyboardType.Ascii
                            else -> KeyboardType.Text
                        },
                        placeholder = if (column.datatype == "date") {
                            stringResource(R.string.mybooks_edit_date_placeholder)
                        } else {
                            ""
                        },
                        enabled = !state.saving
                    )
                }
                if (column.multiple && column.datatype == "text") {
                    Text(
                        text = stringResource(R.string.mybooks_edit_custom_multiple_hint),
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = AppSpace.xs)
                    )
                }
                if (error != null) {
                    Text(
                        text = error,
                        fontSize = AppType.Caption,
                        color = errorColor(),
                        modifier = Modifier.padding(top = AppSpace.xs)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingLogsSection(state: MyBooksEditUiState, viewModel: MyBooksEditViewModel) {
    SectionHeader(stringResource(R.string.mybooks_edit_section_read_logs))
    Column(verticalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
        state.readLogs.forEachIndexed { index, row ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stack = maxWidth < 360.dp
                val error = state.fieldErrors["readlog.$index"]
                Column {
                    if (stack) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                            DateField(
                                label = stringResource(R.string.mybooks_edit_read_start),
                                value = row.start,
                                enabled = !state.saving
                            ) { viewModel.updateReadLog(index, start = it) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DateField(
                                    label = stringResource(R.string.mybooks_edit_read_finish),
                                    value = row.finish,
                                    enabled = !state.saving,
                                    modifier = Modifier.weight(1f)
                                ) { viewModel.updateReadLog(index, finish = it) }
                                DeleteIcon(enabled = !state.saving) { viewModel.removeReadLog(index) }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DateField(
                                label = stringResource(R.string.mybooks_edit_read_start),
                                value = row.start,
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f)
                            ) { viewModel.updateReadLog(index, start = it) }
                            Spacer(Modifier.width(AppSpace.xs))
                            Text(
                                text = stringResource(R.string.mybooks_edit_read_to),
                                fontSize = AppType.Caption,
                                color = AppColors.TextSecondary
                            )
                            Spacer(Modifier.width(AppSpace.xs))
                            DateField(
                                label = stringResource(R.string.mybooks_edit_read_finish),
                                value = row.finish,
                                enabled = !state.saving,
                                modifier = Modifier.weight(1f)
                            ) { viewModel.updateReadLog(index, finish = it) }
                            DeleteIcon(enabled = !state.saving) { viewModel.removeReadLog(index) }
                        }
                    }
                    if (error != null) {
                        Text(
                            text = error,
                            fontSize = AppType.Caption,
                            color = errorColor(),
                            modifier = Modifier.padding(top = AppSpace.xs)
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(AppSpace.xs))
    LiquidGlassTextButton(
        text = stringResource(R.string.mybooks_edit_add_read),
        enabled = !state.saving,
        contentColor = AppColors.TextSecondary,
        onClick = { viewModel.addReadLog() },
        modifier = Modifier.height(44.dp)
    )
    Spacer(Modifier.height(AppSpace.xs))
    Text(
        text = readLogSummary(state.readLogs),
        fontSize = AppType.Caption,
        color = AppColors.TextSecondary
    )
}

@Composable
private fun readLogSummary(logs: List<MyBooksReadLogDraft>): String {
    val finished = logs.filter { it.finish.isNotBlank() }
    val starts = logs.mapNotNull { it.start.takeIf(String::isNotBlank) }
    val finishes = finished.mapNotNull { it.finish.takeIf(String::isNotBlank) }
    return when {
        finished.isNotEmpty() && starts.isNotEmpty() && finishes.isNotEmpty() ->
            stringResource(
                R.string.mybooks_edit_read_summary,
                finished.size,
                starts.maxOrNull().orEmpty(),
                finishes.maxOrNull().orEmpty()
            )
        starts.isNotEmpty() -> stringResource(R.string.mybooks_edit_read_summary_reading, starts.maxOrNull().orEmpty())
        else -> stringResource(R.string.mybooks_edit_read_none)
    }
}

@Composable
private fun DateField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    EditorTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "$label ${stringResource(R.string.mybooks_edit_date_placeholder)}",
        keyboardType = KeyboardType.Ascii,
        enabled = enabled,
        modifier = modifier
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = AppType.Caption,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(bottom = AppSpace.sm)
    )
}

@Composable
private fun ResponsiveFieldPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusSegment(
    selected: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    val labels = listOf(
        stringResource(R.string.mybooks_edit_status_wish),
        stringResource(R.string.mybooks_edit_status_reading),
        stringResource(R.string.mybooks_edit_status_done)
    )
    TwoOrThreeSegment(labels, selected, enabled, onSelected)
}

@Composable
private fun TwoOrThreeSegment(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit
) {
    LiquidGlassSegmentedControl(
        itemCount = labels.size,
        selectedIndex = selected,
        onSelected = onSelected,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        trackHeight = 40.dp
    ) { index, isSelected ->
        Text(
            text = labels[index],
            fontSize = AppType.BodySmall,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun SelectionChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) AppColors.Accent.copy(alpha = 0.18f) else AppColors.BgGray
    val fg = if (selected) AppColors.Accent else AppColors.TextPrimary
    LiquidGlassSurface(
        shape = AppRoundedCornerShape(18.dp),
        fallbackColor = bg,
        modifier = Modifier.heightIn(min = 36.dp),
        enabled = enabled,
        onClick = onClick
    ) {
        Text(
            text = text,
            fontSize = AppType.Caption,
            color = fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DeleteIcon(enabled: Boolean, onClick: () -> Unit) {
    Spacer(Modifier.width(AppSpace.sm))
    LiquidGlassIconButton(
        imageVector = AppIcons.Trash,
        contentDescription = stringResource(R.string.delete),
        onClick = onClick,
        enabled = enabled,
        size = 44.dp,
        iconSize = 18.dp,
        contentColor = AppColors.TextSecondary,
        normalContainerColor = AppColors.BgGray
    )
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 48.dp,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    error: String? = null,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        error != null -> errorColor()
        focused -> AppColors.Accent
        else -> Color.Transparent
    }
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                fontSize = AppType.Caption,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = AppSpace.sm)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(AppRoundedCornerShape(AppRadius.md))
                .background(
                    if (enabled) AppColors.BgGray else AppColors.BgGray.copy(alpha = 0.6f)
                )
                .border(1.dp, borderColor, AppRoundedCornerShape(AppRadius.md))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = AppType.Body,
                    color = AppColors.TextSecondary
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 8,
                textStyle = TextStyle(
                    fontSize = AppType.Body,
                    color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (error != null) {
            Text(
                text = error,
                fontSize = AppType.Caption,
                color = errorColor(),
                modifier = Modifier
                    .padding(start = AppSpace.xs, top = AppSpace.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun errorColor(): Color = ErrorColor
