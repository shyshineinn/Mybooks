package com.huangder.lumibooks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksSyncPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.MyBooksSyncFailure
import com.huangder.lumibooks.data.sync.MyBooksSyncStatus
import com.huangder.lumibooks.data.sync.MyBooksSyncWorkState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.huangder.lumibooks.ui.components.RequestLocalNetworkPermission
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType

/** Same status, content, mode and action layout as WebDAV; backend operations remain separate. */
@Composable
fun MyBooksSettingsDetail(
    requestLocalNetworkPermission: RequestLocalNetworkPermission,
    onConfigure: () -> Unit,
    viewModel: MyBooksSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    val issues by viewModel.syncIssues.collectAsState(initial = emptyList())
    val status by viewModel.syncStatus.collectAsState(initial = MyBooksSyncStatus())
    val pending by viewModel.pendingSyncCount.collectAsState(initial = 0)
    val preferences by viewModel.preferences.collectAsState()
    val enabled = connection.loggedIn && preferences.enabled
    val hasToken = MyBooksConfig.hasWriteToken()
    val lastSync = when {
        status.lastSuccessAt > 0 -> stringResource(R.string.webdav_last_sync_time,
            remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }.format(Date(status.lastSuccessAt)))
        enabled -> stringResource(R.string.mybooks_sync_no_success)
        else -> stringResource(R.string.webdav_not_configured)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md, vertical = AppSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
        SyncConnectionStatusCard(enabled, stringResource(if (enabled) R.string.mybooks_server_connected else R.string.mybooks_server_disconnected),
            listOf(lastSync), syncing)
        if (!enabled) SyncPrimaryButton(stringResource(R.string.mybooks_server_configure), onClick = onConfigure)
        if (enabled && hasToken) {
            SyncSectionCard {
                SyncModeSelector(if (preferences.automatic) "auto" else "manual", viewModel::setSyncMode)
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Divider))
                SyncContentSelector(preferences.content, viewModel::setSyncContent)
            }
        }
        if (enabled) {
            SyncSectionCard {
                if (hasToken) {
                    SyncActionButton(stringResource(if (state.testing) R.string.webdav_test_running else R.string.webdav_test_connection),
                        AppIcons.Pulse, enabled = !state.testing) {
                        requestLocalNetworkPermission(connection.serverUrl, { viewModel.testConnection(connection.serverUrl) }, viewModel::showLocalNetworkPermissionRequired)
                    }
                    if (state.testMessage.isNotBlank()) Text(state.testMessage, fontSize = AppType.Caption,
                        color = if (state.testSucceeded) AppColors.TextSecondary else MaterialTheme.colorScheme.error)
                    SyncActionButton(stringResource(R.string.webdav_sync_now), AppIcons.ArrowsClockwise) {
                        if (!syncing && !state.busy) requestLocalNetworkPermission(connection.serverUrl, viewModel::syncNow, viewModel::showLocalNetworkPermissionRequired)
                    }
                    val result = state.message.ifBlank { status.message }
                    if (result.isNotBlank()) Text(result, fontSize = AppType.Caption,
                        color = if (state.message.isNotBlank() && !state.succeeded || state.message.isBlank() && status.failure != null) MaterialTheme.colorScheme.error else AppColors.TextSecondary)
                    if (pending > 0) Text(stringResource(R.string.mybooks_sync_pending_count, pending), fontSize = AppType.Caption, color = AppColors.TextSecondary)
                }
                SyncActionButton(stringResource(R.string.mybooks_disable), AppIcons.CloudSlash, onClick = viewModel::disable)
                SyncActionButton(stringResource(R.string.webdav_clear_config), AppIcons.Trash, destructive = true, onClick = viewModel::clearConfiguration)
            }
        }
        if (!enabled) MyBooksConnectionMessage(state)
        if (issues.isNotEmpty()) SyncSectionCard {
            Text(stringResource(R.string.mybooks_sync_issues_title), fontSize = AppType.Body, color = AppColors.TextPrimary)
            issues.forEach { issue ->
                Text(issue.title, fontSize = AppType.BodySmall, color = AppColors.TextPrimary)
                Text(issue.message, fontSize = AppType.Caption, color = MaterialTheme.colorScheme.error)
                if (issue.canResolve && enabled) {
                    Text(stringResource(if (issue.readingConflict) R.string.mybooks_reading_conflict else R.string.mybooks_conflict_choice_notice), fontSize = AppType.Caption, color = AppColors.TextSecondary)
                    SyncActionButton(stringResource(R.string.mybooks_conflict_keep_local), AppIcons.CheckCircle, enabled = !syncing) {
                        requestLocalNetworkPermission(connection.serverUrl, { viewModel.resolveSyncIssue(issue.bookId, true, issue.readingConflict) }, viewModel::showLocalNetworkPermissionRequired)
                    }
                    SyncActionButton(stringResource(R.string.mybooks_conflict_use_server), AppIcons.Cloud, enabled = !syncing) {
                        requestLocalNetworkPermission(connection.serverUrl, { viewModel.resolveSyncIssue(issue.bookId, false, issue.readingConflict) }, viewModel::showLocalNetworkPermissionRequired)
                    }
                }
                if (issue.pendingDeletion && enabled) SyncActionButton(stringResource(R.string.mybooks_cancel_pending_delete), AppIcons.ArrowsClockwise, enabled = !syncing) {
                    requestLocalNetworkPermission(connection.serverUrl, { viewModel.cancelPendingDelete(issue.bookId) }, viewModel::showLocalNetworkPermissionRequired)
                }
            }
        }
        SyncDisclosureCard(stringResource(R.string.mybooks_server_title), stringResource(R.string.mybooks_server_disclosure))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun MyBooksConfigurationDetail(
    requestLocalNetworkPermission: RequestLocalNetworkPermission,
    onSaved: () -> Unit,
    viewModel: MyBooksSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val syncing by viewModel.isSyncing.collectAsState()
    var serverUrl by rememberSaveable { mutableStateOf(connection.serverUrl) }
    var username by rememberSaveable { mutableStateOf(connection.username) }
    // 密码不进入可保存的 UI 状态，也不写入 DataStore。
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val busy = state.busy || syncing
    LaunchedEffect(state.loginCompleted) {
        if (state.loginCompleted) { password = ""; onSaved() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md, vertical = AppSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
        SyncSectionCard {
            OutlinedTextField(serverUrl, { serverUrl = it }, enabled = !busy,
                label = { Text(stringResource(R.string.webdav_server_url_label)) },
                placeholder = { Text("https://books.example.com") },
                supportingText = { Text(stringResource(R.string.mybooks_server_url_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true, shape = AppRoundedCornerShape(AppRadius.md), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, enabled = !busy,
                label = { Text(stringResource(R.string.webdav_username_label)) },
                singleLine = true, shape = AppRoundedCornerShape(AppRadius.md), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, enabled = !busy,
                label = { Text(stringResource(R.string.webdav_password_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) AppIcons.EyeSlash else AppIcons.Eye,
                            stringResource(if (visible) R.string.webdav_hide_password else R.string.webdav_show_password))
                    }
                }, singleLine = true, shape = AppRoundedCornerShape(AppRadius.md), modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.mybooks_server_credential_notice), fontSize = AppType.Caption, color = AppColors.TextSecondary)
        }
        SyncPrimaryButton(stringResource(if (state.busy) R.string.mybooks_server_connecting else R.string.mybooks_enable),
            enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank() && (password.isNotEmpty() || connection.loggedIn && serverUrl.trim().trimEnd('/') == connection.serverUrl && username.trim() == connection.username)) {
            requestLocalNetworkPermission(serverUrl, { viewModel.login(serverUrl, username, password) }, viewModel::showLocalNetworkPermissionRequired)
        }
        SyncSectionCard {
            SyncActionButton(stringResource(R.string.webdav_test_connection), AppIcons.Pulse, enabled = !busy && serverUrl.isNotBlank()) {
                requestLocalNetworkPermission(serverUrl, { viewModel.testConnection(serverUrl) }, viewModel::showLocalNetworkPermissionRequired)
            }
        }
        if (state.testMessage.isNotBlank()) Text(state.testMessage, fontSize = AppType.Caption, color = if (state.testSucceeded) AppColors.TextSecondary else MaterialTheme.colorScheme.error)
        MyBooksConnectionMessage(state)
        Spacer(Modifier.height(AppSpace.xl))
    }
}

@Composable
private fun MyBooksConnectionMessage(state: MyBooksSettingsUiState) {
    if (state.message.isNotBlank()) Text(state.message, fontSize = AppType.Caption,
        color = if (state.succeeded) AppColors.TextSecondary else MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth())
}
