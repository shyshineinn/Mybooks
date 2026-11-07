package com.huangder.lumibooks.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.ui.components.LiquidGlassButton
import com.huangder.lumibooks.ui.components.lumiCardSurface
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.*

// Presentation components only; each backend owns its configuration and synchronization.
@Composable
internal fun SyncSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRoundedCornerShape(AppRadius.md))
            .lumiCardSurface(shape = AppRoundedCornerShape(AppRadius.md))
            .padding(AppSpace.md),
        verticalArrangement = Arrangement.spacedBy(AppSpace.sm),
        content = content
    )
}

@Composable
internal fun SyncConnectionStatusCard(
    isConnected: Boolean,
    statusText: String,
    details: List<String>,
    isBusy: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRoundedCornerShape(AppRadius.md))
            .lumiCardSurface(shape = AppRoundedCornerShape(AppRadius.md))
            .padding(AppSpace.md)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    imageVector = if (isConnected) AppIcons.CheckCircle else AppIcons.CloudSlash,
                    contentDescription = null,
                    tint = if (isConnected) AppColors.Accent else AppColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(AppSpace.sm))
                Text(
                    text = stringResource(R.string.sync_status_card_title),
                    fontSize = AppType.Body,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary
                )
            }
            if (isBusy) {
                CircularProgressIndicator(
                    color = AppColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(AppSpace.sm))
        Text(
            text = statusText,
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        details.filter { it.isNotBlank() }.forEach { detail ->
            Text(
                text = detail,
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
internal fun SyncPrimaryButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    LiquidGlassButton(
        onClick = onClick,
        enabled = enabled,
        tintedColor = AppColors.Accent,
        shape = AppRoundedCornerShape(25.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
    ) {
        Text(
            label,
            fontSize = AppType.BodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else AppColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SyncActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.error
        enabled -> AppColors.TextPrimary
        else -> AppColors.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = AppSpace.md, vertical = AppSpace.md + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else AppColors.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(AppSpace.md))
        Text(
            text = label,
            fontSize = AppType.Body,
            color = contentColor
        )
    }
}

@Composable
internal fun SyncModeSelector(
    selectedMode: String,
    onModeChange: (String) -> Unit,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpace.xs)
    ) {
        Text(
            text = stringResource(R.string.sync_mode_label),
            fontSize = AppType.BodySmall,
            color = AppColors.TextSecondary
        )
        Spacer(Modifier.height(AppSpace.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpace.sm)
        ) {
            SyncModePill(
                label = stringResource(R.string.sync_mode_auto),
                selected = selectedMode == "auto",
                onClick = { onModeChange("auto") },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
            SyncModePill(
                label = stringResource(R.string.sync_mode_manual),
                selected = selectedMode == "manual",
                onClick = { onModeChange("manual") },
                modifier = Modifier.weight(1f),
                enabled = enabled
            )
        }
    }
}

@Composable
private fun SyncModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .clip(AppRoundedCornerShape(25.dp))
            .background(
                if (selected) AppColors.Accent else AppColors.BgGray
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = AppType.BodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else AppColors.TextSecondary
        )
    }
}

@Composable
internal fun SyncContentSelector(
    selection: com.huangder.lumibooks.domain.model.SyncContentSelection,
    onContentChange: (com.huangder.lumibooks.domain.model.SyncContent, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpace.sm)
    ) {
        Text(
            text = stringResource(R.string.webdav_sync_content_title),
            fontSize = AppType.BodySmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            modifier = Modifier.padding(horizontal = AppSpace.md, vertical = AppSpace.xs)
        )
        Text(
            text = stringResource(R.string.webdav_sync_content_description),
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(
                start = AppSpace.md,
                end = AppSpace.md,
                bottom = AppSpace.sm
            )
        )
        SyncContentRow(
            title = stringResource(R.string.webdav_sync_content_books),
            description = stringResource(R.string.webdav_sync_content_books_description),
            checked = selection.bookFiles,
            onCheckedChange = { onContentChange(com.huangder.lumibooks.domain.model.SyncContent.BOOK_FILES, it) }
        )
        SyncContentRow(
            title = stringResource(R.string.webdav_sync_content_profile_settings),
            description = stringResource(R.string.webdav_sync_content_profile_settings_description),
            checked = selection.profileAndSettings,
            onCheckedChange = { onContentChange(com.huangder.lumibooks.domain.model.SyncContent.PROFILE_AND_SETTINGS, it) }
        )
        SyncContentRow(
            title = stringResource(R.string.webdav_sync_content_library),
            description = stringResource(R.string.webdav_sync_content_library_description),
            checked = selection.libraryOrganization,
            onCheckedChange = { onContentChange(com.huangder.lumibooks.domain.model.SyncContent.LIBRARY_ORGANIZATION, it) }
        )
        SyncContentRow(
            title = stringResource(R.string.webdav_sync_content_reading_data),
            description = stringResource(R.string.webdav_sync_content_reading_data_description),
            checked = selection.readingData,
            onCheckedChange = { onContentChange(com.huangder.lumibooks.domain.model.SyncContent.READING_DATA, it) }
        )
    }
}

@Composable
private fun SyncContentRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(
                start = AppSpace.md,
                end = AppSpace.sm,
                top = AppSpace.xs,
                bottom = AppSpace.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) AppColors.Accent else Color.Transparent)
                .then(
                    if (checked) Modifier else Modifier.border(
                        width = 1.5.dp,
                        color = AppColors.TextSecondary,
                        shape = CircleShape
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(AppSpace.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = AppType.Body,
                color = AppColors.TextPrimary
            )
            Text(
                text = description,
                fontSize = AppType.Caption,
                color = AppColors.TextSecondary
            )
        }
    }
}

@Composable
internal fun SyncDisclosureCard(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRoundedCornerShape(AppRadius.md))
            .background(AppColors.BgGray)
            .padding(AppSpace.md),
        verticalArrangement = Arrangement.spacedBy(AppSpace.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.ArrowsClockwise, null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(AppSpace.sm))
            Text(
                title,
                fontSize = AppType.BodySmall,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            description,
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary
        )
    }
}

// ─── Sync mode pill selector ────────────────────────────────────────
