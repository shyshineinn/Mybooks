package com.huangder.lumibooks.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.huangder.lumibooks.data.local.entity.MyBooksPendingDeleteEntity
import com.huangder.lumibooks.data.local.entity.MyBooksSyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MyBooksSyncStateDao {
    @Query("SELECT * FROM mybooks_sync_state WHERE bookId = :bookId")
    suspend fun get(bookId: String): MyBooksSyncStateEntity?

    @Query("SELECT * FROM mybooks_sync_state")
    suspend fun getAll(): List<MyBooksSyncStateEntity>

    @Query("SELECT * FROM mybooks_sync_state WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<MyBooksSyncStateEntity?>

    @Query("SELECT * FROM mybooks_sync_state")
    fun observeAll(): Flow<List<MyBooksSyncStateEntity>>

    @Upsert
    suspend fun upsert(state: MyBooksSyncStateEntity)

    @Query("DELETE FROM mybooks_sync_state WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    @Query("SELECT * FROM mybooks_pending_delete")
    suspend fun getPendingDeletes(): List<MyBooksPendingDeleteEntity>

    @Query("SELECT * FROM mybooks_pending_delete WHERE remoteId = :remoteId")
    suspend fun getPendingDelete(remoteId: Long): MyBooksPendingDeleteEntity?

    @Upsert
    suspend fun upsertPendingDelete(item: MyBooksPendingDeleteEntity)

    @Query("DELETE FROM mybooks_pending_delete WHERE remoteId = :remoteId")
    suspend fun deletePendingDelete(remoteId: Long)
}
