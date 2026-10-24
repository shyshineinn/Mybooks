package com.huangder.lumibooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType

/**
 * 下拉操作的顶部提示（首页「松手继续阅读」、书架「松手同步服务器书库」共用）。
 *
 * 提示随下拉进度淡入并从上方 12dp 滑入；[topPadding] 由调用方给出，
 * 需要避开顶部工具栏/胶囊按钮，否则文字会盖住按钮。
 */
internal const val PULL_TO_REFRESH_THRESHOLD_DP = 96f

/** 提示文字随下拉进度下滑的距离，进度为 1 时落到锚点位置。 */
internal const val PULL_TO_REFRESH_HINT_SLIDE_DP = 12f

/**
 * 下拉进度 `0f..1f`。
 *
 * 只有内容被向下拉出（`offsetPx > 0`）时才可见；底部回弹的负位移与静置都返回 0。
 */
internal fun pullToRefreshProgress(offsetPx: Float, thresholdPx: Float): Float {
    if (offsetPx <= 0f || thresholdPx <= 0f) return 0f
    return (offsetPx / thresholdPx).coerceIn(0f, 1f)
}

/** 松手时是否应触发下拉动作。原路推回（位移回落）时不满足阈值，因此不做任何事。 */
internal fun shouldTriggerPullActionOnRelease(releasedOffsetPx: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && releasedOffsetPx >= thresholdPx

/**
 * 绘制在列表上方的下拉提示。调用方负责算出 [progress]，
 * 因此拖动期间只有这一小块重组。
 */
@Composable
internal fun PullToRefreshHint(
    text: String,
    progress: Float,
    topPadding: Dp,
    slideDistanceDp: Float = PULL_TO_REFRESH_HINT_SLIDE_DP,
    modifier: Modifier = Modifier
) {
    if (progress <= 0f) return
    val slidePx = with(LocalDensity.current) { slideDistanceDp.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .graphicsLayer {
                alpha = progress
                translationY = -slidePx * (1f - progress)
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = AppSpace.lg),
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
