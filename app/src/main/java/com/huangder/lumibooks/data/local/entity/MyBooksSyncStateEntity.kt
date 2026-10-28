package com.huangder.lumibooks.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 单本服务器书的三方同步基线:
 * baseJson = 上次成功同步的服务器详情; overridesJson = 本地改动;
 * editedAtJson = 字段 -> 本地修改时间; baseTagsJson = 上次服务器标签集合。
 */
@Entity(tableName = "mybooks_sync_state")
data class MyBooksSyncStateEntity(
    @PrimaryKey val bookId: String,
    val remoteId: Long,
    val serverUpdatedAt: String = "",
    val baseJson: String = "",
    val overridesJson: String = "{}",
    val editedAtJson: String = "{}",
    val baseTagsJson: String = "[]",
    val coverDirty: Boolean = false,
    val coverEditedAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val remoteDeleted: Boolean = false,
    @ColumnInfo(defaultValue = "'[]'") val conflictFieldsJson: String = "[]"
)

@Entity(tableName = "mybooks_pending_delete")
data class MyBooksPendingDeleteEntity(
    @PrimaryKey val remoteId: Long,
    val bookId: String,
    val deletedAt: Long,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
    val expectedRevision: Long? = null,
    @ColumnInfo(defaultValue = "''") val expectedUuid: String = "",
    @ColumnInfo(defaultValue = "''") val expectedUpdatedAt: String = "",
    @ColumnInfo(defaultValue = "''") val title: String = ""
)
