package com.huangder.lumibooks.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.huangder.lumibooks.domain.model.MenuAnimationStyle
import com.huangder.lumibooks.ui.animation.VisualMotion
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.LocalMenuAnimationStyle
import com.huangder.lumibooks.ui.theme.LocalMotionEnabled
import kotlin.math.roundToInt

/**
 * Shared anchored glass morph used by compact popups.
 *
 * The caller supplies fixed start/target rectangles in root coordinates. The
 * component owns one progress value for position, size, radius, content alpha
 * and the glass rim, so no layout measurement feeds back into the target.
 */
@Composable
fun LiquidGlassMorphPopup(
    startRect: Rect,
    targetRect: Rect,
    closeRequested: Boolean,
    onDismiss: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    startRadius: Dp = 8.dp,
    /** Menus settle on a 24dp continuous corner; popups share that radius. */
    targetRadius: Dp = 24.dp,
    /** Content can keep the original row alignment when the glass is inset. */
    startContentRect: Rect = startRect,
    startContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.(enabled: Boolean) -> Unit
) {
    val density = LocalDensity.current
    val motionEnabled = LocalMotionEnabled.current
    val normalStyle = LocalMenuAnimationStyle.current == MenuAnimationStyle.NORMAL
    val progress = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(startRect, targetRect, normalStyle) {
        settled = false
        if (motionEnabled) {
            if (normalStyle) {
                progress.animateTo(1f, VisualMotion.tween(190))
            } else {
                progress.animateTo(
                    1f,
                    VisualMotion.spring(
                        dampingRatio = 0.9f,
                        stiffness = 300f,
                        visibilityThreshold = 0.001f
                    )
                )
            }
        } else {
            progress.snapTo(1f)
        }
        settled = true
    }
    LaunchedEffect(closeRequested) {
        if (!closeRequested) return@LaunchedEffect
        settled = false
        if (motionEnabled) {
            if (normalStyle) {
                progress.animateTo(0f, VisualMotion.tween(140))
            } else {
                progress.animateTo(
                    0f,
                    VisualMotion.spring(
                        dampingRatio = 0.9f,
                        stiffness = 360f,
                        visibilityThreshold = 0.001f
                    )
                )
            }
        } else {
            progress.snapTo(0f)
        }
        currentOnFinished()
    }

    val rectState = remember(startRect, targetRect, normalStyle) {
        derivedStateOf {
            if (normalStyle) targetRect else lerpRect(startRect, targetRect, progress.value)
        }
    }
    val rowAlpha = { if (normalStyle) 0f else startContentAlpha(progress.value) }
    val targetContentAlpha = { if (normalStyle) 1f else targetContentAlpha(progress.value) }
    val outlineAlpha = { if (normalStyle) 1f else outlineRevealAlpha(progress.value) }
    val startRadiusPx = with(density) { startRadius.toPx() }
    val targetRadiusPx = with(density) { targetRadius.toPx() }
    val shapeRadius = if (normalStyle) {
        targetRadius
    } else {
        with(density) { lerp(startRadiusPx, targetRadiusPx, progress.value).toDp() }
    }
    val shape = com.huangder.lumibooks.ui.theme.AppShapes.rounded(
        shapeRadius,
        com.huangder.lumibooks.ui.theme.AppShapes.usesContinuousCorners(
            com.huangder.lumibooks.ui.theme.LocalAppTheme.current
        )
    )
    val startContentWidthDp = with(density) { startContentRect.width.toDp() }
    val startContentHeightDp = with(density) { startContentRect.height.toDp() }
    val targetWidthDp = with(density) { targetRect.width.toDp() }
    val targetHeightDp = with(density) { targetRect.height.toDp() }

    BackHandler {
        if (!closeRequested) onDismiss()
    }
    Box(Modifier.fillMaxSize().pointerInput(onDismiss) { dismissGestures(onDismiss) })

    Box(
        modifier
            .absoluteOffset {
                IntOffset(rectState.value.left.roundToInt(), rectState.value.top.roundToInt())
            }
            .layout { measurable, constraints ->
                val bounds = rectState.value
                val width = constraints.constrainWidth(bounds.width.roundToInt().coerceAtLeast(1))
                val height = constraints.constrainHeight(bounds.height.roundToInt().coerceAtLeast(1))
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                if (normalStyle && motionEnabled) {
                    alpha = progress.value
                    val scale = lerp(0.94f, 1f, progress.value)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                }
            }
            .testTag("liquid-glass-morph-card")
    ) {
        OutlinedLiquidGlassSurface(
            shape = shape,
            fallbackColor = AppColors.CardBg,
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart,
            outlineAlpha = outlineAlpha()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.shape = shape
                        clip = true
                    }
            ) {
                Box(
                    Modifier
                        .absoluteOffset {
                            val rect = rectState.value
                            IntOffset(
                                (startContentRect.left - rect.left).roundToInt(),
                                (startContentRect.top - rect.top).roundToInt()
                            )
                        }
                        .requiredSize(startContentWidthDp, startContentHeightDp)
                        .graphicsLayer { alpha = rowAlpha() }
                        .clip(shape)
                        .clearAndSetSemantics {},
                    content = startContent
                )
                Box(
                    Modifier
                        .absoluteOffset {
                            val rect = rectState.value
                            IntOffset(
                                (targetRect.left - rect.left).roundToInt(),
                                (targetRect.top - rect.top).roundToInt()
                            )
                        }
                        .requiredSize(targetWidthDp, targetHeightDp)
                        .graphicsLayer { alpha = targetContentAlpha() }
                        .clip(shape)
                        .then(
                            if (!settled && !closeRequested) {
                                Modifier.clearAndSetSemantics {}
                            } else {
                                Modifier
                            }
                        )
                ) {
                    content(settled && !closeRequested)
                }
            }
        }
    }
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect = Rect(
    left = lerp(start.left, end.left, fraction),
    top = lerp(start.top, end.top, fraction),
    right = lerp(start.right, end.right, fraction),
    bottom = lerp(start.bottom, end.bottom, fraction)
)

private fun startContentAlpha(progress: Float): Float {
    val t = ((progress - 0.05f) / 0.3f).coerceIn(0f, 1f)
    return 1f - t * t * (3f - 2f * t)
}

private fun targetContentAlpha(progress: Float): Float {
    val t = ((progress - 0.4f) / 0.32f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun outlineRevealAlpha(progress: Float): Float {
    val t = ((progress - 0.12f) / 0.58f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dismissGestures(
    onDismiss: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.positionChange().getDistance() > viewConfiguration.touchSlop) moved = true
            change.consume()
            if (!change.pressed) break
        }
        if (!moved) onDismiss()
    }
}
