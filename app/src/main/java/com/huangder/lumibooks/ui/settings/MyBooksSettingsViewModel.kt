package com.huangder.lumibooks.ui.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.MyBooksAuthClient
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksEndpoint
import com.huangder.lumibooks.data.sync.MyBooksSyncDirection
import com.huangder.lumibooks.data.sync.MyBooksSyncPreferencesStore
import com.huangder.lumibooks.data.sync.MyBooksSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import com.huangder.lumibooks.domain.repository.BookRepository
import com.huangder.lumibooks.data.sync.MyBooksBookTransfer
import com.huangder.lumibooks.data.sync.MyBooksTransferProgress
import com.huangder.lumibooks.data.sync.canUploadMyBooksBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyBooksSettingsUiState(
    val busy: Boolean = false,
    val message: String = "",
    val succeeded: Boolean = false,
    val loginCompleted: Boolean = false,
    val transferProgress: MyBooksTransferProgress? = null,
    val testing: Boolean = false,
    val testMessage: String = "",
    val testSucceeded: Boolean = false
)

@HiltViewModel
class MyBooksSettingsViewModel @Inject constructor(
    private val syncManager: MyBooksSyncManager,
    private val preferencesStore: MyBooksSyncPreferencesStore,
    private val syncScheduler: com.huangder.lumibooks.data.sync.MyBooksSyncScheduler,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val state = MutableStateFlow(MyBooksSettingsUiState())
    val uiState = state.asStateFlow()
    val connection = MyBooksConfig.connection
    val isSyncing = syncManager.isSyncing
    val syncIssues = syncManager.syncIssues
    val syncStatus = syncManager.syncStatus
    val pendingSyncCount = syncManager.pendingSyncCount
    val backgroundSync = syncScheduler.workState
    val preferences = preferencesStore.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.huangder.lumibooks.data.sync.MyBooksSyncPreferences(enabled = false))
    fun setSyncMode(mode: String) {
        if (mode !in setOf("auto", "manual")) return
        viewModelScope.launch { preferencesStore.setAutomatic(mode == "auto") }
    }
    fun setSyncContent(content: com.huangder.lumibooks.domain.model.SyncContent, checked: Boolean) {
        viewModelScope.launch { preferencesStore.setContent(content, checked) }
    }
    fun resolveSyncIssue(bookId: String, useLocal: Boolean, reading: Boolean = false) = runOperation {
        val result = if (reading) syncManager.resolveReadingConflict(bookId, useLocal) else syncManager.resolveConflict(bookId, useLocal)
        check(result.success) { result.message }
        result.message
    }
    fun cancelPendingDelete(bookId: String) = runOperation {
        val result = syncManager.cancelPendingDelete(bookId)
        check(result.success) { result.message }
        result.message
    }

    private fun runOperation(allowDuringSync: Boolean = false, block: suspend () -> String) {
        if (state.value.busy || !allowDuringSync && syncManager.isSyncing.value) return
        state.update { it.copy(busy = true, message = "", succeeded = false, loginCompleted = false, transferProgress = null) }
        viewModelScope.launch {
            try {
                val message = block()
                state.value = state.value.copy(busy = false, message = message, succeeded = true, transferProgress = null)
            } catch (cancelled: CancellationException) {
                state.value = state.value.copy(busy = false)
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(busy = false, message = error.message ?: context.getString(R.string.mybooks_connection_failed)) }
            }
        }
    }
    fun showLocalNetworkPermissionRequired() {
        state.value = MyBooksSettingsUiState(message = context.getString(R.string.mybooks_server_local_network_required))
    }
    fun testConnection(serverUrl: String) {
        if (state.value.testing) return
        state.update { it.copy(testing = true, testMessage = "") }
        viewModelScope.launch {
            try {
                val url = MyBooksEndpoint.normalize(serverUrl)
                val client = MyBooksAuthClient(url)
                client.testConnection()
                if (url == MyBooksConfig.SERVER_URL && MyBooksConfig.hasWriteToken()) client.checkSession(MyBooksConfig.adminToken)
                state.update { it.copy(testing = false, testSucceeded = true, testMessage = context.getString(R.string.mybooks_server_test_success)) }
            } catch (cancelled: CancellationException) { state.update { it.copy(testing = false) }; throw cancelled }
            catch (error: Exception) { state.update { it.copy(testing = false, testSucceeded = false, testMessage = error.message.orEmpty()) } }
        }
    }
    fun disable() = runOperation(allowDuringSync = true) {
        preferencesStore.setEnabled(false)
        context.getString(R.string.mybooks_disabled)
    }
    fun clearConfiguration() = runOperation(allowDuringSync = true) {
        preferencesStore.setEnabled(false)
        syncScheduler.cancel()
        syncManager.clearConfiguration()
        context.getString(R.string.mybooks_config_cleared)
    }
    fun login(serverUrl: String, username: String, password: String) = runOperation {
        val url = MyBooksEndpoint.normalize(serverUrl)
        val reuseSession = password.isBlank() && url == MyBooksConfig.SERVER_URL && username.trim() == MyBooksConfig.connection.value.username && MyBooksConfig.hasWriteToken()
        require(username.trim().isNotEmpty() && (password.isNotEmpty() || reuseSession)) { context.getString(R.string.mybooks_server_credentials_required) }
        syncManager.checkServerChange(url)
        val client = MyBooksAuthClient(url)
        client.testConnection()
        if (reuseSession) {
            client.checkSession(MyBooksConfig.adminToken)
            preferencesStore.setEnabled(true)
            syncScheduler.scheduleImmediate()
            state.value = state.value.copy(loginCompleted = true)
            return@runOperation context.getString(R.string.mybooks_server_login_success)
        }
        val token = client.login(username.trim(), password, "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
        try {
            syncManager.saveConnection(url, username.trim(), token)
        } catch (error: Exception) {
            // 保存本地配置失败时撤销刚签发的会话，避免留下无用授权。
            runCatching { client.logout(token) }
            throw error
        }
        state.value = state.value.copy(loginCompleted = true)
        context.getString(R.string.mybooks_server_login_success)
    }
    fun syncNow() = runOperation {
        val result = syncManager.syncNow()
        check(result.success) { result.message }
        result.message
    }
    fun logout() = runOperation {
        val revoked = syncManager.logoutConnection()
        context.getString(if (revoked) R.string.mybooks_server_logged_out else R.string.mybooks_server_local_logout)
    }
}
