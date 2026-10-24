package com.huangder.lumibooks.ui.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.MyBooksApiClient
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksMetaCache
import com.huangder.lumibooks.data.sync.RemoteBookFormats
import com.huangder.lumibooks.data.sync.ServerBookMeta
import com.huangder.lumibooks.data.sync.resolveUrl
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.ui.bookshelf.DetailRow
import com.huangder.lumibooks.ui.components.AnnotationTagChips
import com.huangder.lumibooks.ui.components.LiquidGlassTextButton
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class MyBooksMetaUiState(
    val meta: ServerBookMeta? = null,
    val loading: Boolean = false,
    val retry: () -> Unit = {}
)

/**
 * 详情页只加载一次服务器元数据，内容卡与文件卡共用，
 * 避免拆卡后重复请求；先显示缓存，再后台刷新。
 */
@Composable
fun rememberMyBooksMeta(book: Book): MyBooksMetaUiState {
    val remoteId = MyBooksConfig.remoteIdOf(book.id)
    val context = LocalContext.current
    val cache = remember(context) { MyBooksMetaCache(context) }
    val api = remember { MyBooksApiClient() }
    var retryToken by remember(remoteId) { mutableIntStateOf(0) }
    var meta by remember(remoteId) { mutableStateOf<ServerBookMeta?>(null) }
    var loading by remember(remoteId) { mutableStateOf(remoteId != null) }

    LaunchedEffect(remoteId, retryToken) {
        val id = remoteId
        if (id == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        meta = withContext(Dispatchers.IO) {
            cache.read(id)?.let { cached ->
                runCatching { ServerBookMeta.fromJson(cached, id) }.getOrNull()
            }
        }
        try {
            val raw = api.fetchBookJson(id)
            withContext(Dispatchers.IO) { cache.write(id, raw) }
            meta = ServerBookMeta.fromJson(raw, id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 刷新失败保留缓存内容。
        } finally {
            loading = false
        }
    }

    return MyBooksMetaUiState(
        meta = meta,
        loading = loading,
        retry = { retryToken++ }
    )
}

@Composable
fun MyBooksContentRows(
    metaState: MyBooksMetaUiState,
    notesState: MyBooksNotesUiState? = null,
    syncBadge: MyBooksSyncBadge? = null
) {
    val meta = metaState.meta
    if (meta == null) {
        if (!metaState.loading) MetaRetryRow(metaState.retry)
        return
    }

    if (hasMeaningfulSummary(meta.summary)) {
        SummaryRow(meta.summary.trim())
    }
    val context = LocalContext.current
    buildServerDetailRows(meta).forEach { row ->
        DetailRow(row.label, row.value, row.url != null, onClick = {
            row.url?.let { openServerUrl(context, it) }
        })
    }
    ReadingRecordBlock(meta)
    if (notesState != null) {
        NotesStatusRow(notesState)
    }
    if (syncBadge != null) {
        val value = when {
            syncBadge.pendingCount > 0 -> stringResource(R.string.mybooks_sync_pending, syncBadge.pendingCount)
            syncBadge.lastError != null -> stringResource(R.string.mybooks_sync_failed)
            else -> null
        }
        if (value != null) {
            DetailRow(stringResource(R.string.mybooks_sync_state), value, false)
        }
    }
}

@Composable
private fun NotesStatusRow(state: MyBooksNotesUiState) {
    DetailRow(stringResource(R.string.mybooks_notes_title), notesStatusText(state), false)
}

@Composable
private fun notesStatusText(state: MyBooksNotesUiState): String {
    val base = when {
        state.syncing -> stringResource(R.string.mybooks_notes_status_syncing)
        state.uploading -> stringResource(R.string.mybooks_notes_status_uploading)
        state.loading -> stringResource(R.string.mybooks_notes_status_loading)
        state.error != null && state.errorWasUpload -> stringResource(R.string.mybooks_notes_status_upload_failed)
        state.error != null -> stringResource(R.string.mybooks_notes_status_sync_failed)
        state.localHas && state.serverHas && state.upToDate -> stringResource(
            R.string.mybooks_notes_status_local,
            formatNoteSize(state.localSize),
            formatNoteTime(state.serverUpdatedAt)
        )
        state.serverHas -> stringResource(R.string.mybooks_notes_status_remote_newer)
        state.localHas -> stringResource(R.string.mybooks_notes_status_server_missing)
        else -> stringResource(R.string.mybooks_notes_status_none)
    }
    return if (MyBooksConfig.hasWriteToken()) base else base + stringResource(R.string.mybooks_notes_readonly_suffix)
}

private fun formatNoteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    return if (bytes >= 1024L * 1024L) {
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
}

private fun formatNoteTime(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(raw))
    }.getOrElse { raw.take(16) }
}

@Composable
fun MyBooksFileRows(book: Book, metaState: MyBooksMetaUiState) {
    val meta = metaState.meta ?: return
    fileRowValue(meta, book.isCloudOnly)?.let { value ->
        DetailRow("电子书文件", value, false)
    }
}

@Composable
private fun SummaryRow(summary: String) {
    var expanded by remember(summary) { mutableStateOf(false) }
    val canExpand = summary.length > 120
    val motionEnabled = LocalMotionEnabled.current
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = if (motionEnabled) tween(220) else snap())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (canExpand) {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { expanded = !expanded }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = AppSpace.md, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "简介",
                fontSize = AppType.Body,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (canExpand) {
                Icon(
                    imageVector = if (expanded) AppIcons.CaretUp else AppIcons.CaretDown,
                    contentDescription = null,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            summary,
            modifier = Modifier.padding(horizontal = AppSpace.md).padding(bottom = 14.dp),
            fontSize = AppType.BodySmall,
            lineHeight = 20.sp,
            color = AppColors.TextSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReadingRecordBlock(meta: ServerBookMeta) {
    val labels = readingLogLabels(meta)
    if (labels.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.md, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "阅读记录",
                    fontSize = AppType.Body,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                readCountLabel(meta)?.let { count ->
                    Text(count, fontSize = AppType.Caption, color = AppColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(6.dp))
            AnnotationTagChips(labels, icon = AppIcons.BookOpen)
        }
        return
    }

    readingDateLabel(meta)?.let { dates ->
        DetailRow("阅读日期", dates, false)
        return
    }
    if (meta.readCount > 0) {
        DetailRow("阅读次数", "${meta.readCount} 次", false)
    }
}

@Composable
private fun MetaRetryRow(onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "同步后可查看简介、评分等完整信息",
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        LiquidGlassTextButton(
            text = "重试",
            onClick = onRetry,
            modifier = Modifier.height(44.dp),
            contentColor = AppColors.Accent
        )
    }
}

private fun openServerUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(resolveUrl(MyBooksConfig.SERVER_URL, url))
            )
        )
    }
}
