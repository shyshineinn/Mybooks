package com.helloac.mybooks.remote

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huangder.lumibooks.data.sync.BookDownloadState
import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.ui.components.LiquidGlassSurface
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 书籍详情页里的「图书信息」卡片：只展示服务器书籍的内容元数据
 * （简介/标签/状态/评分/系列/出版信息/识别号/自定义字段/可下载格式），
 * 不含域名、来源标识、服务器地址、服务器时间戳等任何服务器信息。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyBooksBookInfoCard(
    book: Book,
    downloadState: BookDownloadState?,
    onDownload: () -> Unit
) {
    val remoteId = MyBooksConfig.remoteIdOf(book.id) ?: return
    val context = LocalContext.current
    val metaCache = remember(context) { MyBooksMetaCache(context) }
    var retryToken by remember { mutableStateOf(0) }
    var meta by remember { mutableStateOf<ServerBookMeta?>(null) }

    LaunchedEffect(remoteId, retryToken) {
        meta = withContext(Dispatchers.IO) {
            metaCache.read(remoteId)?.let { cached ->
                runCatching { ServerBookMeta.fromJson(cached, remoteId) }.getOrNull()
            }
        }
        // 后台刷新最新详情（失败时保留缓存）
        runCatching {
            val raw = MyBooksApiClient().fetchBookJson(remoteId)
            metaCache.write(remoteId, raw)
            val fresh = ServerBookMeta.fromJson(raw, remoteId)
            if (fresh.summary.isNotBlank() || fresh.tags.isNotEmpty()) meta = fresh
        }
    }

    LiquidGlassSurface(
        AppRoundedCornerShape(AppRadius.lg),
        AppColors.CardBg,
        Modifier.fillMaxWidth(),
        contentScrimColor = Color.Transparent
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpace.md, vertical = AppSpace.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "图书信息",
                    fontSize = AppType.Section,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
            }

            val current = meta
            if (current == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.md, vertical = AppSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "同步后可查看简介、标签等完整信息",
                        fontSize = AppType.Body,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { retryToken++ }) { Text("重试") }
                }
            } else {
                ServerMetaContent(meta = current, book = book, downloadState = downloadState, onDownload = onDownload)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerMetaContent(
    meta: ServerBookMeta,
    book: Book,
    downloadState: BookDownloadState?,
    onDownload: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    if (meta.summary.isNotBlank()) {
        Text(
            meta.summary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.md),
            fontSize = AppType.Body,
            lineHeight = 20.sp,
            color = AppColors.TextPrimary,
            maxLines = if (expanded) 500 else 4,
            overflow = TextOverflow.Ellipsis
        )
        if (!expanded && meta.summary.length > 120) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md)) {
                TextButton(onClick = { expanded = true }) { Text("展开") }
            }
        } else if (expanded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md)) {
                TextButton(onClick = { expanded = false }) { Text("收起") }
            }
        }
    }

    if (meta.tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            meta.tags.forEach { tag ->
                Text(
                    tag,
                    modifier = Modifier
                        .clip(AppRoundedCornerShape(AppRadius.md))
                        .background(AppColors.BgGray)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = AppType.Caption,
                    color = AppColors.TextSecondary
                )
            }
        }
    }

    val statusText = statusLabel(meta.status)
    val ratingText = meta.rating?.let { String.format(Locale.US, "%.1f", it) }
    val seriesText = meta.seriesLabel
    if (statusText.isNotBlank() || ratingText != null || !seriesText.isNullOrBlank()) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpace.md),
            horizontalArrangement = Arrangement.spacedBy(AppSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
        ) {
            if (statusText.isNotBlank()) {
                InfoChip("状态", statusText)
            }
            ratingText?.let { InfoChip("评分", "★ $it") }
            seriesText?.let { InfoChip("系列", it) }
        }
    }

    val publishText = listOfNotNull(meta.publisher, meta.pubYear).joinToString(" · ")
    val isbnText = meta.isbn
    if (publishText.isNotBlank() || isbnText != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md)) {
            if (publishText.isNotBlank()) InfoLine("出版", publishText)
            isbnText?.let { InfoLine("ISBN", it) }
        }
    }

    if (meta.startDate != null || meta.finishDate != null) {
        val dates = listOfNotNull(
            meta.startDate?.let { "开始 $it" },
            meta.finishDate?.let { "读完 $it" }
        ).joinToString(" · ")
        if (dates.isNotBlank()) InfoLine("阅读", dates)
    }

    if (meta.notesUrl != null) {
        val context = LocalContext.current
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(meta.notesUrl)))
                    }
                }
                .padding(horizontal = AppSpace.md, vertical = 6.dp)
        ) {
            Text("笔记链接", fontSize = AppType.Body, color = AppColors.TextPrimary)
            Spacer(Modifier.width(AppSpace.md))
            Text("查看", fontSize = AppType.BodySmall, color = AppColors.Accent)
        }
    }

    if (meta.identifiers.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md)) {
            meta.identifiers.forEach { identifier ->
                val label = identifier.label ?: identifier.type
                val value = identifier.value
                if (label != null && value != null) {
                    val context = LocalContext.current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (identifier.url != null) {
                                    Modifier.clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(identifier.url))
                                            )
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$label：",
                            fontSize = AppType.BodySmall,
                            color = AppColors.TextSecondary,
                            modifier = Modifier.width(92.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            value,
                            fontSize = AppType.BodySmall,
                            color = if (identifier.url != null) AppColors.Accent else AppColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (meta.customDisplay.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md)) {
            meta.customDisplay.forEach { (label, value) ->
                InfoLine(label, value)
            }
        }
    }

    if (meta.files.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = AppSpace.sm)) {
            meta.files.forEach { file ->
                if (RemoteBookFormats.isSupported(file.format)) {
                    InfoLine(
                        RemoteBookFormats.displayFormat(file.format),
                        "可下载 · ${formatSize(file.size)}"
                    )
                }
            }
        }
    }

    if (book.isCloudOnly) {
        when (downloadState) {
            is BookDownloadState.Downloading -> {
                Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md, vertical = AppSpace.md)) {
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(AppRoundedCornerShape(4.dp)),
                        color = AppColors.Accent,
                        trackColor = AppColors.Divider
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "正在下载 ${(downloadState.progress * 100).toInt()}%",
                        fontSize = AppType.Caption,
                        color = AppColors.TextSecondary
                    )
                }
            }
            is BookDownloadState.Failed -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpace.md, vertical = AppSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        downloadState.message,
                        fontSize = AppType.BodySmall,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDownload) { Text("重试") }
                }
            }
            else -> {
                val preferred = RemoteBookFormats.preferredFile(meta.files)
                if (preferred != null) {
                    Text(
                        "下载 ${RemoteBookFormats.displayFormat(preferred.format)} · ${formatSize(preferred.size)}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpace.md, vertical = AppSpace.md)
                            .clip(AppRoundedCornerShape(AppRadius.md))
                            .background(AppColors.Accent)
                            .clickable(onClick = onDownload)
                            .padding(vertical = 14.dp),
                        fontSize = AppType.Body,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.OnAccent,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else if (meta.files.isEmpty()) {
                    Text(
                        "暂无电子书文件可下载",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpace.md, vertical = AppSpace.md),
                        fontSize = AppType.Body,
                        color = AppColors.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(label, fontSize = AppType.Caption, color = AppColors.TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = AppType.Body,
            fontWeight = FontWeight.Bold,
            color = AppColors.TextPrimary,
            modifier = Modifier.widthIn(max = 150.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "$label：",
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.width(92.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            value,
            fontSize = AppType.BodySmall,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun statusLabel(status: String): String = when (status.lowercase()) {
    "want", "wish" -> "想读"
    "reading" -> "在读"
    "done", "finished", "read" -> "已读"
    else -> status
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        else -> String.format(Locale.US, "%.0f KB", bytes / kb)
    }
}
