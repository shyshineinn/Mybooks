package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.local.dao.SyncStateDao
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncIdentityStore @Inject constructor(
    private val syncStateDao: SyncStateDao
) {
    @Volatile
    private var cachedDeviceId: String? = null
    private val deviceIdMutex = Mutex()

    suspend fun deviceId(): String = deviceIdMutex.withLock {
        cachedDeviceId?.let { return@withLock it }
        val stored = syncStateDao.getValue(DEVICE_ID_KEY)?.takeIf { it.isNotBlank() }
        val resolved = stored ?: UUID.randomUUID().toString().also {
            syncStateDao.putState(SyncStateEntity(DEVICE_ID_KEY, it))
        }
        cachedDeviceId = resolved
        resolved
    }

    companion object {
        const val DEVICE_ID_KEY = "device_id"
    }
}
