package com.huangder.lumibooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.huangder.lumibooks.ui.theme.LocalAppTheme
import com.huangder.lumibooks.ui.theme.LocalLiquidGlassCapability
import com.huangder.lumibooks.ui.theme.LocalLumiBackgroundBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val LocalLiquidGlassMorphPopupBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Records the visible page for morph popups, including text, images and controls.
 * Keep [content] and [popup] in separate slots: only the page is captured, and only
 * the popup receives that capture, so neither can recursively sample itself.
 * Keep [active] true until the popup's return animation has finished.
 */
@Composable
fun LiquidGlassMorphPopupHost(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
    popup: @Composable BoxScope.() -> Unit
) {
    val pageBackdrop = rememberLayerBackdrop()
    val backgroundBackdrop = LocalLiquidGlassBackdrop.current ?: LocalLumiBackgroundBackdrop.current
    val popupBackdrop = if (backgroundBackdrop != null) {
        rememberCombinedBackdrop(backgroundBackdrop, pageBackdrop)
    } else pageBackdrop
    val capturePage = active && LocalAppTheme.current == "liquid_glass" &&
        LocalLiquidGlassCapability.current.supported
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().then(if (capturePage) Modifier.layerBackdrop(pageBackdrop) else Modifier),
            content = content
        )
        CompositionLocalProvider(LocalLiquidGlassMorphPopupBackdrop provides popupBackdrop) {
            popup()
        }
    }
}
