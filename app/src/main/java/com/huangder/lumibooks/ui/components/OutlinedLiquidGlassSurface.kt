package com.huangder.lumibooks.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalCardOutlinesEnabled
import com.huangder.lumibooks.ui.theme.LocalIsDarkTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalLumiBackgroundBackdrop
import com.huangder.lumibooks.ui.theme.LocalReduceTransparency
import com.kyant.backdrop.Backdrop

/**
 * Shared glass surface for cards and popups.
 *
 * Liquid glass never draws [Modifier.cardOutline] itself, so this wrapper adds the
 * user-selected outline for that theme. [outlineAlpha] lets morphing surfaces fade
 * their rim together with the geometry; static popups leave it at 1f.
 */
@Composable
fun OutlinedLiquidGlassSurface(
    shape: Shape,
    fallbackColor: Color,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    contentScrimColor: Color = Color.Transparent,
    transparencyOverride: Float? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    outlineAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    val liquidTheme = LocalAppTheme.current == "liquid_glass"
    val outlinesEnabled = LocalCardOutlinesEnabled.current
    val resolvedOutlineAlpha = outlineAlpha.coerceIn(0f, 1f)
    val activeBackdrop = backdrop
        ?: LocalLiquidGlassBackdrop.current
        ?: LocalLumiBackgroundBackdrop.current
    // Real liquid glass already has a refractive rim/highlight. A black stroke
    // fights those optics, so only fallback surfaces opt into the user outline.
    val liquidOpticsActive = liquidTheme &&
        LocalLiquidGlassCapability.current.supported &&
        LocalLiquidGlassLayer.current < 3 &&
        activeBackdrop != null &&
        !LocalReduceTransparency.current
    Box(modifier) {
        LiquidGlassSurface(
            shape = shape,
            fallbackColor = fallbackColor,
            modifier = Modifier.matchParentSize(),
            backdrop = backdrop,
            contentScrimColor = contentScrimColor,
            transparencyOverride = transparencyOverride,
            interactive = false,
            contentAlignment = contentAlignment,
            outlineAlpha = resolvedOutlineAlpha,
            content = content
        )
        if (liquidTheme && !liquidOpticsActive && outlinesEnabled && resolvedOutlineAlpha > 0f) {
            val outlineColor = if (LocalIsDarkTheme.current) Color.White else Color.Black
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        width = 0.5.dp,
                        color = outlineColor.copy(alpha = resolvedOutlineAlpha),
                        shape = shape
                    )
            )
        }
    }
}
