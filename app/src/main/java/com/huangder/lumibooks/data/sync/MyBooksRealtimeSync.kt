package com.huangder.lumibooks.data.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyBooksRealtimeSync @Inject constructor(
    manager: MyBooksSyncManager,
    scheduler: MyBooksSyncScheduler,
    private val stateStore: MyBooksSyncStateStore,
    preferences: MyBooksSyncPreferencesStore
) {
    private val events = MyBooksSyncEventLoop(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        canSync = { preferences.automatic() && MyBooksConfig.hasWriteToken() && stateStore.status.first().failure != MyBooksSyncFailure.AUTH },
        synchronize = { manager.syncReadingChanges() },
        durableUpload = { scheduler.scheduleImmediate() },
        onError = { Log.e("MyBooksSync", "Automatic sync or reader checkpoint failed", it) }
    )
    fun start() = events.start(stateStore.automaticReadingChanges)
    fun onAppBackgrounded() = events.onAppBackgrounded()
    fun saveReaderState(save: suspend () -> Unit): Job = events.persistThenUpload(save)
}

internal class MyBooksSyncEventLoop(
    private val scope: CoroutineScope,
    private val canSync: suspend () -> Boolean,
    private val synchronize: suspend () -> Unit,
    private val durableUpload: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val editDebounceMs: Long = 5_000
) {
    private var started = false
    @Synchronized
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start(changes: Flow<Map<String, String>>) {
        if (started) return
        started = true
        requestFullSync()
        scope.launch {
            changes.debounce(editDebounceMs).collectLatest { pending ->
                if (pending.isNotEmpty()) safely { if (canSync()) synchronize() }
            }
        }
    }
    fun onAppBackgrounded() = requestFullSync()
    fun requestFullSync() { scope.launch { safely { if (canSync()) durableUpload() } } }
    fun persistThenUpload(save: suspend () -> Unit): Job = scope.launch {
        safely { save(); if (canSync()) durableUpload() }
    }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onError(error) }
    }
}
