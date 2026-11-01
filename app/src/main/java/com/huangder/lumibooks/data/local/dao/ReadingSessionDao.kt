package com.huangder.lumibooks.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.huangder.lumibooks.data.local.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE startTime >= :startMs AND startTime < :endMs ORDER BY startTime DESC")
    fun getSessionsBetween(startMs: Long, endMs: Long): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startTime DESC")
    fun getSessionsByBook(bookId: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY startTime DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): ReadingSessionEntity?

    @Insert
    suspend fun insertSession(session: ReadingSessionEntity): Long

    @Query("UPDATE reading_sessions SET note = :note, mood = :mood, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSessionNote(id: Long, note: String?, mood: String?, updatedAt: Long)

    @Query("DELETE FROM reading_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}
