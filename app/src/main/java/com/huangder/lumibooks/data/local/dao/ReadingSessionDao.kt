package com.huangder.lumibooks.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
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

    @Query("SELECT DISTINCT bookId FROM reading_sessions")
    fun getBookIdsWithSessions(): Flow<List<String>>

    @Query("SELECT * FROM reading_sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: Long): ReadingSessionEntity?

    @Query("SELECT * FROM reading_sessions WHERE sessionKey = :sessionKey LIMIT 1")
    suspend fun getSessionByKey(sessionKey: String): ReadingSessionEntity?

    @Insert
    suspend fun insertSession(session: ReadingSessionEntity): Long

    @Update
    suspend fun updateSession(session: ReadingSessionEntity)

    @Query("UPDATE reading_sessions SET note = :note, mood = :mood, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSessionNote(id: Long, note: String?, mood: String?, updatedAt: Long)

    @Query("DELETE FROM reading_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query(
        "SELECT * FROM reading_sessions " +
            "WHERE (:bookId IS NULL OR bookId = :bookId) " +
            "AND (:startDate IS NULL OR date >= :startDate) " +
            "AND (:endDate IS NULL OR date <= :endDate) " +
            "AND (:mode IS NULL " +
            "  OR (:mode = 'scroll' AND mode IN ('continuous', 'scroll', 'vertical')) " +
            "  OR (:mode = 'paged' AND mode NOT IN ('continuous', 'scroll', 'vertical'))) " +
            "ORDER BY startTime DESC LIMIT :limit"
    )
    fun observeSessionsFiltered(
        bookId: String?,
        startDate: String?,
        endDate: String?,
        mode: String?,
        limit: Int
    ): Flow<List<ReadingSessionEntity>>
}
