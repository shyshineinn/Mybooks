package com.huangder.lumibooks.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import com.huangder.lumibooks.ui.components.RequestLocalNetworkPermission
import com.huangder.lumibooks.ui.icons.AppIcons
import com.huangder.lumibooks.ui.theme.AppColors
import com.huangder.lumibooks.ui.theme.AppRadius
import com.huangder.lumibooks.ui.theme.AppRoundedCornerShape
import com.huangder.lumibooks.ui.theme.AppSpace
import com.huangder.lumibooks.ui.theme.AppType

/** 与 WebDAV 共用状态页/配置页、分组卡片和按钮，主题材质由公共组件处理。 */
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
    val busy = state.busy || syncing
    Column(Modifier.fillMaxWidth().padding(horizontal = AppSpace.md, vertical = AppSpace.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpace.md)) {
        WebdavSectionCard {
            Text(stringResource(if (connection.loggedIn) R.string.mybooks_server_connected else R.string.mybooks_server_login_required),
                fontSize = AppType.Body, color = AppColors.TextPrimary)
            Text(connection.serverUrl, fontSize = AppType.BodySmall, color = AppColors.TextSecondary)
            if (connection.username.isNotBlank()) Text(connection.username, fontSize = AppType.Caption, color = AppColors.TextSecondary)
            if (busy) CircularProgressIndicator(color = AppColors.Accent)
        }
        WebdavPrimaryButton(label = stringResource(R.string.mybooks_server_configure), enabled = !busy, onClick = onConfigure)
        if (connection.loggedIn) {
            WebdavSectionCard {
                WebdavSecondaryButton(stringResource(R.string.webdav_test_connection), AppIcons.Pulse, enabled = !busy) {
                    requestLocalNetworkPermission(connection.serverUrl,
                        { viewModel.testConnection(connection.serverUrl) }, viewModel::showLocalNetworkPermissionRequired)
                }
                WebdavSecondaryButton(stringResource(R.string.webdav_sync_now), AppIcons.ArrowsClockwise, enabled = !busy) {
                    requestLocalNetworkPermission(connection.serverUrl, viewModel::syncNow, viewModel::showLocalNetworkPermissionRequired)
                }
                WebdavSecondaryButton(stringResource(R.string.mybooks_server_logout), AppIcons.CloudSlash, destructive = true, enabled = !busy) {
                    viewModel.logout()
                }
            }
        }
        MyBooksConnectionMessage(state)
        if (issues.isNotEmpty()) {
            WebdavSectionCard {
                Text(stringResource(R.string.mybooks_sync_issues_title), fontSize = AppType.Body, color = AppColors.TextPrimary)
                for (issue in issues) {
                    Text(issue.title, fontSize = AppType.BodySmall, color = AppColors.TextPrimary)
                    Text(issue.message, fontSize = AppType.Caption, color = MaterialTheme.colorScheme.error)
                    if (issue.canResolve && connection.loggedIn) {
                        Text(stringResource(R.string.mybooks_conflict_choice_notice), fontSize = AppType.Caption, color = AppColors.TextSecondary)
                        WebdavSecondaryButton(stringResource(R.string.mybooks_conflict_keep_local), AppIcons.CheckCircle, enabled = !busy) {
                            requestLocalNetworkPermission(connection.serverUrl,
                                { viewModel.resolveSyncIssue(issue.bookId, true) }, viewModel::showLocalNetworkPermissionRequired)
                        }
                        WebdavSecondaryButton(stringResource(R.string.mybooks_conflict_use_server), AppIcons.Cloud, enabled = !busy) {
                            requestLocalNetworkPermission(connection.serverUrl,
                                { viewModel.resolveSyncIssue(issue.bookId, false) }, viewModel::showLocalNetworkPermissionRequired)
                        }
                    }
                    if (issue.pendingDeletion && connection.loggedIn) {
                        WebdavSecondaryButton(stringResource(R.string.mybooks_cancel_pending_delete), AppIcons.ArrowsClockwise, enabled = !busy) {
                            requestLocalNetworkPermission(connection.serverUrl,
                                { viewModel.cancelPendingDelete(issue.bookId) }, viewModel::showLocalNetworkPermissionRequired)
                        }
                    }
                    Spacer(Modifier.height(AppSpace.sm))
                }
            }
        }
        Text(stringResource(R.string.mybooks_server_disclosure), fontSize = AppType.Caption, color = AppColors.TextSecondary)
        Spacer(Modifier.height(AppSpace.xl))
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
        WebdavSectionCard {
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
        WebdavPrimaryButton(stringResource(if (state.busy) R.string.mybooks_server_connecting else R.string.mybooks_server_login),
            enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
            requestLocalNetworkPermission(serverUrl, { viewModel.login(serverUrl, username, password) }, viewModel::showLocalNetworkPermissionRequired)
        }
        WebdavSectionCard {
            WebdavSecondaryButton(stringResource(R.string.webdav_test_connection), AppIcons.Pulse, enabled = !busy && serverUrl.isNotBlank()) {
                requestLocalNetworkPermission(serverUrl, { viewModel.testConnection(serverUrl) }, viewModel::showLocalNetworkPermissionRequired)
            }
        }
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
