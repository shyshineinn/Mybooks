package com.huangder.lumibooks.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppType

/** Shared measurements for the compact, centered status selector. */
object LiquidGlassMorphSelectorDefaults {
    val Width = 102.dp
    val HeaderHeight = 28.dp
    val RowHeight = 48.dp
    val HorizontalPadding = 8.dp
    val VerticalPadding = 8.dp
    val OptionShape = 12.dp
    const val MaxFontScale = 1.8f

    fun scale(fontScale: Float): Float = fontScale.coerceIn(1f, MaxFontScale)
    fun headerHeight(fontScale: Float): Dp = HeaderHeight * scale(fontScale)
    fun rowHeight(fontScale: Float): Dp = RowHeight * scale(fontScale)

    fun contentHeight(fontScale: Float): Dp =
        VerticalPadding * 2 + headerHeight(fontScale) + rowHeight(fontScale) * 3
}

/** Compact single-choice content with the shared morph-popup typography and spacing. */
@Composable
fun <T> LiquidGlassMorphSelector(
    title: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    optionModifier: (T) -> Modifier = { Modifier }
) {
    val fontScale = LiquidGlassMorphSelectorDefaults.scale(LocalDensity.current.fontScale)
    Column(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(vertical = LiquidGlassMorphSelectorDefaults.VerticalPadding)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(LiquidGlassMorphSelectorDefaults.headerHeight(fontScale))
                .padding(horizontal = LiquidGlassMorphSelectorDefaults.HorizontalPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = AppType.Caption,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(LiquidGlassMorphSelectorDefaults.rowHeight(fontScale))
                    .clip(AppRoundedCornerShape(LiquidGlassMorphSelectorDefaults.OptionShape))
                    .then(optionModifier(option))
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .padding(horizontal = LiquidGlassMorphSelectorDefaults.HorizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = optionLabel(option),
                    fontSize = AppType.Body,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) AppColors.Accent else AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
