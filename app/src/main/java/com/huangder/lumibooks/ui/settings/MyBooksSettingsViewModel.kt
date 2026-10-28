package com.huangder.lumibooks.ui.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huangder.lumibooks.R
import com.huangder.lumibooks.data.sync.MyBooksAuthClient
import com.huangder.lumibooks.data.sync.MyBooksConfig
import com.huangder.lumibooks.data.sync.MyBooksEndpoint
import com.huangder.lumibooks.data.sync.MyBooksSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyBooksSettingsUiState(
    val busy: Boolean = false,
    val message: String = "",
    val succeeded: Boolean = false,
    val loginCompleted: Boolean = false
)

@HiltViewModel
class MyBooksSettingsViewModel @Inject constructor(
    private val syncManager: MyBooksSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val state = MutableStateFlow(MyBooksSettingsUiState())
    val uiState = state.asStateFlow()
    val connection = MyBooksConfig.connection
    val isSyncing = syncManager.isSyncing
    val syncIssues = syncManager.syncIssues

    fun resolveSyncIssue(bookId: String, useLocal: Boolean) = runOperation {
        val result = syncManager.resolveConflict(bookId, useLocal)
        check(result.success) { result.message }
        result.message
    }
    fun cancelPendingDelete(bookId: String) = runOperation {
        val result = syncManager.cancelPendingDelete(bookId)
        check(result.success) { result.message }
        result.message
    }

    private fun runOperation(block: suspend () -> String) {
        if (state.value.busy || syncManager.isSyncing.value) return
        state.value = MyBooksSettingsUiState(busy = true)
        viewModelScope.launch {
            try {
                val message = block()
                state.value = state.value.copy(busy = false, message = message, succeeded = true)
            } catch (cancelled: CancellationException) {
                state.value = state.value.copy(busy = false)
                throw cancelled
            } catch (error: Exception) {
                state.value = MyBooksSettingsUiState(message = error.message ?: context.getString(R.string.mybooks_connection_failed))
            }
        }
    }
    fun showLocalNetworkPermissionRequired() {
        state.value = MyBooksSettingsUiState(message = context.getString(R.string.mybooks_server_local_network_required))
    }
    fun testConnection(serverUrl: String) = runOperation {
        val url = MyBooksEndpoint.normalize(serverUrl)
        val client = MyBooksAuthClient(url)
        client.testConnection()
        if (url == MyBooksConfig.SERVER_URL && MyBooksConfig.hasWriteToken()) {
            client.checkSession(MyBooksConfig.adminToken)
        }
        context.getString(R.string.mybooks_server_test_success)
    }
    fun login(serverUrl: String, username: String, password: String) = runOperation {
        val url = MyBooksEndpoint.normalize(serverUrl)
        require(username.trim().isNotEmpty() && password.isNotEmpty()) { context.getString(R.string.mybooks_server_credentials_required) }
        syncManager.checkServerChange(url)
        val client = MyBooksAuthClient(url)
        client.testConnection()
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
