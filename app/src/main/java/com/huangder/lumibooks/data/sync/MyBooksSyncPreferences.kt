package com.huangder.lumibooks.data.sync

import com.huangder.lumibooks.data.local.dao.SyncStateDao
import com.huangder.lumibooks.data.local.entity.SyncStateEntity
import com.huangder.lumibooks.domain.model.SyncContent
import com.huangder.lumibooks.domain.model.SyncContentSelection
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class MyBooksSyncPreferences(
    val automatic: Boolean = true,
    val enabled: Boolean = true,
    val content: SyncContentSelection = SyncContentSelection()
) {
    internal fun json(): String = JSONObject().put("automatic", automatic).put("enabled", enabled)
        .put("bookFiles", content.bookFiles).put("profileAndSettings", content.profileAndSettings)
        .put("libraryOrganization", content.libraryOrganization).put("readingData", content.readingData).toString()
    companion object {
        internal fun parse(value: String?): MyBooksSyncPreferences = runCatching {
            val root = JSONObject(value ?: "{}")
            MyBooksSyncPreferences(root.optBoolean("automatic", true), root.optBoolean("enabled", true), SyncContentSelection(
                root.optBoolean("bookFiles", true), root.optBoolean("profileAndSettings", true),
                root.optBoolean("libraryOrganization", true), root.optBoolean("readingData", true)))
        }.getOrDefault(MyBooksSyncPreferences())
    }
}

@Singleton
class MyBooksSyncPreferencesStore @Inject constructor(private val dao: SyncStateDao) {
    private val edits = Mutex()
    val preferences = dao.observeValue(KEY).map(MyBooksSyncPreferences::parse)
    suspend fun current() = preferences.first()
    suspend fun automatic() = current().let { it.enabled && it.automatic }
    suspend fun setAutomatic(enabled: Boolean) = edits.withLock { save(current().copy(automatic = enabled)) }
    suspend fun setEnabled(enabled: Boolean) = edits.withLock { save(current().copy(enabled = enabled)) }
    suspend fun setContent(content: SyncContent, enabled: Boolean) = edits.withLock {
        val old = current()
        val selection = when (content) {
            SyncContent.BOOK_FILES -> old.content.copy(bookFiles = enabled)
            SyncContent.PROFILE_AND_SETTINGS -> old.content.copy(profileAndSettings = enabled)
            SyncContent.LIBRARY_ORGANIZATION -> old.content.copy(libraryOrganization = enabled)
            SyncContent.READING_DATA -> old.content.copy(readingData = enabled)
        }
        dao.putStates(listOf(SyncStateEntity(KEY, old.copy(content = selection).json()), SyncStateEntity(MyBooksSyncManager.KEY_CURSOR, "")))
    }
    suspend fun clear() = edits.withLock { dao.deleteState(KEY) }
    private suspend fun save(value: MyBooksSyncPreferences) = dao.putState(SyncStateEntity(KEY, value.json()))
    companion object { private const val KEY = "mybooks_sync_preferences" }
}
