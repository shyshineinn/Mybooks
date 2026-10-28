package com.huangder.lumibooks.ui.settings
import androidx.compose.foundation.layout.heightIn
import com.huangder.lumibooks.ui.icons.AppIcons

import com.huangder.lumibooks.ui.components.lumiCardSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.R
import com.huangder.lumibooks.domain.model.WebdavConfig
import com.huangder.lumibooks.domain.model.WebdavSyncContent
import com.huangder.lumibooks.ui.components.LiquidGlassButton
import com.huangder.lumibooks.ui.components.RequestLocalNetworkPermission
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─── Status page ────────────────────────────────────────────────────

@Composable
fun WebdavSettingsDetail(
    viewModel: SettingsViewModel,
    requestLocalNetworkPermission: RequestLocalNetworkPermission,
    onConfigure: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.webdavConfig
    val isEnabled = config.enabled
    val hasToken = uiState.webdavHasToken
    val lastSyncText = if (config.lastSyncTime > 0) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        stringResource(R.string.webdav_last_sync_time, fmt.format(Date(config.lastSyncTime)))
    } else {
        stringResource(R.string.webdav_not_configured)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.md, vertical = AppSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpace.md)
    ) {
        // Status card
        SyncConnectionStatusCard(
            isConnected = isEnabled,
            statusText = stringResource(
                if (isEnabled) R.string.webdav_enabled_status else R.string.webdav_not_configured
            ),
            details = listOf(lastSyncText),
            isBusy = uiState.isWebdavSyncing
        )

        if (!isEnabled) {
            SyncPrimaryButton(
                label = stringResource(R.string.webdav_configure),
                onClick = onConfigure
            )
        }

        if (isEnabled && hasToken) {
            SyncSectionCard {
                SyncModeSelector(
                    selectedMode = config.syncMode,
                    onModeChange = { viewModel.saveWebdavSyncMode(it) }
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Divider))
                SyncContentSelector(
                    selection = com.huangder.lumibooks.domain.model.SyncContentSelection(config.syncBookFiles, config.syncProfileAndSettings, config.syncLibraryOrganization, config.syncReadingData),
                    onContentChange = { content, checked -> viewModel.setWebdavSyncContent(WebdavSyncContent.valueOf(content.name), checked) }
                )
            }
        }

        if (isEnabled) {
            SyncSectionCard {
                if (hasToken) {
                    SyncActionButton(
                        label = stringResource(
                            if (uiState.webdavTesting) R.string.webdav_test_running
                            else R.string.webdav_test_connection
                        ),
                        icon = AppIcons.Pulse,
                        enabled = !uiState.webdavTesting,
                        onClick = {
                            requestLocalNetworkPermission(
                                config.serverUrl,
                                viewModel::testWebdavConnection,
                                viewModel::showWebdavTestLocalNetworkPermissionRequired
                            )
                        }
                    )
                    if (uiState.webdavTestResult.isNotBlank()) {
                        Text(
                            text = uiState.webdavTestResult,
                            fontSize = AppType.Caption,
                            color = if (uiState.webdavTestSucceeded) AppColors.TextSecondary
                                else MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SyncActionButton(
                        label = stringResource(R.string.webdav_sync_now),
                        icon = AppIcons.ArrowsClockwise,
                        onClick = {
                            if (!uiState.isWebdavSyncing) {
                                requestLocalNetworkPermission(
                                    config.serverUrl,
                                    viewModel::syncWebdavNow,
                                    viewModel::showWebdavSyncLocalNetworkPermissionRequired
                                )
                            }
                        }
                    )
                    if (uiState.webdavSyncResult.isNotBlank()) {
                        Text(
                            text = uiState.webdavSyncResult,
                            fontSize = AppType.Caption,
                            color = if (uiState.webdavSyncSucceeded) AppColors.TextSecondary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                SyncActionButton(
                    label = stringResource(R.string.webdav_disable),
                    icon = AppIcons.CloudSlash,
                    onClick = { viewModel.disableWebdav(clearKey = false) }
                )
                SyncActionButton(
                    label = stringResource(R.string.webdav_clear_config),
                    icon = AppIcons.Trash,
                    destructive = true,
                    onClick = { viewModel.clearWebdavConfig() }
                )
            }
        }

        SyncDisclosureCard(stringResource(R.string.webdav_third_party_title), stringResource(R.string.webdav_third_party_notice))
        Spacer(Modifier.height(32.dp))
    }
}

// ─── Configuration page ─────────────────────────────────────────────

@Composable
fun WebdavConfigurationDetail(
    viewModel: SettingsViewModel,
    requestLocalNetworkPermission: RequestLocalNetworkPermission,
    onSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentConfig = uiState.webdavConfig
    val hasToken = uiState.webdavHasToken

    var draftServerUrl by rememberSaveable { mutableStateOf(currentConfig.serverUrl) }
    var draftUsername by rememberSaveable { mutableStateOf(currentConfig.username) }
    var draftSyncPath by rememberSaveable { mutableStateOf(currentConfig.syncPath) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var serverUrlError by rememberSaveable { mutableStateOf(false) }
    var usernameError by rememberSaveable { mutableStateOf(false) }

    fun saveConfiguration() {
        val config = WebdavConfig(
            enabled = true,
            serverUrl = draftServerUrl.trim(),
            username = draftUsername.trim(),
            syncPath = draftSyncPath.trim().ifEmpty { "LumiBooks" },
            lastSyncTime = currentConfig.lastSyncTime,
            syncMode = currentConfig.syncMode,
            syncBookFiles = currentConfig.syncBookFiles,
            syncProfileAndSettings = currentConfig.syncProfileAndSettings,
            syncLibraryOrganization = currentConfig.syncLibraryOrganization,
            syncReadingData = currentConfig.syncReadingData
        )
        viewModel.enableWebdav(config, password)
        onSaved()
    }

    fun validateAndSave() {
        serverUrlError = false
        usernameError = false
        var valid = true

        if (draftServerUrl.isBlank()) {
            serverUrlError = true
            valid = false
        }
        if (draftUsername.isBlank()) {
            usernameError = true
            valid = false
        }
        if (!valid) return

        requestLocalNetworkPermission(
            draftServerUrl,
            { saveConfiguration() },
            viewModel::showWebdavLocalNetworkPermissionRequired
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpace.md, vertical = AppSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpace.md)
    ) {
        SyncSectionCard {
        // Server URL
        OutlinedTextField(
            value = draftServerUrl,
            onValueChange = { draftServerUrl = it; serverUrlError = false },
            label = { Text(stringResource(R.string.webdav_server_url_label)) },
            placeholder = { Text(stringResource(R.string.webdav_server_url_hint)) },
            supportingText = { Text(stringResource(R.string.webdav_server_url_help)) },
            isError = serverUrlError,
            singleLine = true,
            shape = AppRoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // Username
        OutlinedTextField(
            value = draftUsername,
            onValueChange = { draftUsername = it; usernameError = false },
            label = { Text(stringResource(R.string.webdav_username_label)) },
            isError = usernameError,
            singleLine = true,
            shape = AppRoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // Password
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.webdav_password_label)) },
            placeholder = { Text(stringResource(R.string.webdav_password_hint)) },
            visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) AppIcons.EyeSlash
                            else AppIcons.Eye,
                        contentDescription = if (passwordVisible)
                            stringResource(R.string.webdav_hide_password)
                        else stringResource(R.string.webdav_show_password)
                    )
                }
            },
            supportingText = if (hasToken) {
                { Text(stringResource(R.string.webdav_token_saved)) }
            } else null,
            singleLine = true,
            shape = AppRoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // Sync path (optional)
        OutlinedTextField(
            value = draftSyncPath,
            onValueChange = { draftSyncPath = it },
            label = { Text(stringResource(R.string.webdav_sync_path_hint)) },
            singleLine = true,
            shape = AppRoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        // Key storage notice
        Text(
            text = stringResource(R.string.webdav_token_storage_notice),
            fontSize = AppType.Caption,
            color = AppColors.TextSecondary
        )
        }

        Spacer(Modifier.height(AppSpace.sm))

        // Save button — uses LiquidGlassButton matching ExternalTts pattern
        SyncPrimaryButton(
            label = stringResource(R.string.webdav_enable),
            onClick = ::validateAndSave
        )
    }
}
