package com.huangder.lumibooks.data.repository

import com.huangder.lumibooks.data.local.dao.BookDuration
import com.huangder.lumibooks.data.local.dao.BookmarkDao
import com.huangder.lumibooks.data.local.dao.NoteDao
import com.huangder.lumibooks.data.local.dao.ReadingRecordDao
import com.huangder.lumibooks.data.local.dao.ReadingSessionDao
import com.huangder.lumibooks.data.local.database.AppDatabase
import com.huangder.lumibooks.data.local.entity.BookmarkEntity
import com.huangder.lumibooks.data.local.entity.AnnotationTagEntity
import com.huangder.lumibooks.data.local.entity.NoteEntity
import com.huangder.lumibooks.data.local.entity.ReadingRecordEntity
import com.huangder.lumibooks.data.local.entity.ReadingSessionEntity
import com.huangder.lumibooks.domain.model.AnnotationEditPlan
import com.huangder.lumibooks.domain.model.AnnotationTags
import com.huangder.lumibooks.domain.model.Bookmark
import com.huangder.lumibooks.domain.model.DailyTotal
import com.huangder.lumibooks.domain.model.Note
import com.huangder.lumibooks.domain.model.MIN_EFFECTIVE_READING_SESSION_MS
import com.huangder.lumibooks.domain.model.ReadingRecord
import com.huangder.lumibooks.domain.model.ReadingSession
import com.huangder.lumibooks.domain.model.ReadingSessionDayContribution
import com.huangder.lumibooks.domain.model.splitReadingSessionDuration
import com.huangder.lumibooks.domain.repository.ReadingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import androidx.room.withTransaction
import org.json.JSONObject
import javax.inject.Inject
import java.util.UUID
import com.huangder.lumibooks.data.sync.SyncIdentityStore
import com.huangder.lumibooks.data.local.dao.SyncStateDao
import com.huangder.lumibooks.data.local.entity.SyncTombstoneEntity

class ReadingRepositoryImpl @Inject constructor(
    private val readingRecordDao: ReadingRecordDao,
    private val bookmarkDao: BookmarkDao,
    private val noteDao: NoteDao,
    private val syncStateDao: SyncStateDao,
    private val syncIdentityStore: SyncIdentityStore,
    private val database: AppDatabase
) : ReadingRepository {

    private val readingSessionDao: ReadingSessionDao get() = database.readingSessionDao()

    override fun getRecordsByDate(date: String): Flow<List<ReadingRecord>> {
        return readingRecordDao.getRecordsByDate(date).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecordsBetweenDates(startDate: String, endDate: String): Flow<List<ReadingRecord>> {
        return readingRecordDao.getRecordsBetweenDates(startDate, endDate).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getTotalDurationByDate(date: String): Flow<Long?> {
        return readingRecordDao.getTotalDurationByDate(date)
    }

    override fun getTotalDurationBetweenDates(startDate: String, endDate: String): Flow<Long?> {
        return readingRecordDao.getTotalDurationBetweenDates(startDate, endDate)
    }

    override suspend fun insertRecord(record: ReadingRecord) {
        val deviceId = record.sourceDeviceId.ifBlank { syncIdentityStore.deviceId() }
        readingRecordDao.insertRecord(record.copy(sourceDeviceId = deviceId).toEntity())
    }

    override suspend fun getRecordByBookAndDate(bookId: String, date: String): ReadingRecord? {
        return readingRecordDao.getRecordByBookAndDate(
            bookId,
            date,
            syncIdentityStore.deviceId()
        )?.toDomain()
    }

    override suspend fun updateRecordDuration(recordId: Long, additionalDuration: Long, endTime: Long) {
        readingRecordDao.updateRecordDuration(recordId, additionalDuration, endTime)
    }

    override fun observeReadingSessions(startMs: Long, endMs: Long): Flow<List<ReadingSession>> =
        readingSessionDao.getSessionsBetween(startMs, endMs).map { rows -> rows.map { it.toDomain() } }

    override fun observeAllReadingSessions(): Flow<List<ReadingSession>> =
        readingSessionDao.getAllSessions().map { rows -> rows.map { it.toDomain() } }

    override fun observeRecentReadingSessions(limit: Int): Flow<List<ReadingSession>> =
        readingSessionDao.getRecentSessions(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeReadingSessionsByBook(bookId: String): Flow<List<ReadingSession>> =
        readingSessionDao.getSessionsByBook(bookId).map { rows -> rows.map { it.toDomain() } }

    override fun observeSessionBookIds(): Flow<List<String>> =
        readingSessionDao.getBookIdsWithSessions()

    override fun observeSessionsFiltered(
        bookId: String?,
        startDate: String?,
        endDate: String?,
        mode: String?,
        limit: Int
    ): Flow<List<ReadingSession>> =
        readingSessionDao.observeSessionsFiltered(bookId, startDate, endDate, mode, limit)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun getReadingSession(id: Long): ReadingSession? =
        readingSessionDao.getSessionById(id)?.toDomain()

    override suspend fun saveReadingSession(session: ReadingSession): ReadingSession =
        database.withTransaction {
            val deviceId = session.sourceDeviceId.ifBlank { syncIdentityStore.deviceId() }
            val sessionKey = session.sessionKey.ifBlank { UUID.randomUUID().toString() }
            val existing = readingSessionDao.getSessionByKey(sessionKey)
            val oldContributions = existing?.contributionMap().orEmpty()
            val newContributions = if (session.duration >= MIN_EFFECTIVE_READING_SESSION_MS) {
                splitReadingSessionDuration(session.startTime, session.endTime, session.duration)
                    .associateBy { it.date }
            } else {
                emptyMap()
            }
            val normalized = session.copy(
                sessionKey = sessionKey,
                sourceDeviceId = deviceId,
                dailyContributions = newContributions.mapValues { it.value.duration },
                contributedDuration = newContributions.values.sumOf { it.duration },
                note = session.note ?: existing?.note,
                mood = session.mood ?: existing?.mood
            )
            val row = normalized.toEntity().copy(id = existing?.id ?: 0L)
            val sessionId = if (existing == null) {
                readingSessionDao.insertSession(row)
            } else {
                readingSessionDao.updateSession(row)
                existing.id
            }
            applyDailyContributionDelta(
                bookId = normalized.bookId,
                deviceId = deviceId,
                oldContributions = oldContributions,
                newContributions = newContributions,
                session = normalized
            )
            normalized.copy(id = sessionId)
        }

    override suspend fun updateReadingSessionNote(id: Long, note: String?, mood: String?): Boolean =
        runCatching {
            readingSessionDao.updateSessionNote(
                id,
                note?.takeIf { it.isNotBlank() },
                mood?.takeIf { it.isNotBlank() },
                System.currentTimeMillis()
            )
            true
        }.getOrDefault(false)

    override suspend fun deleteReadingSession(id: Long): ReadingSession? =
        database.withTransaction {
            val entity = readingSessionDao.getSessionById(id) ?: return@withTransaction null
            val deviceId = entity.sourceDeviceId.ifBlank { syncIdentityStore.deviceId() }
            val contributions = entity.contributionMap()
            for ((date, contribution) in contributions) {
                adjustDailyRecord(
                    bookId = entity.bookId,
                    date = date,
                    deviceId = deviceId,
                    delta = -contribution.duration,
                    startTime = entity.startTime,
                    endTime = entity.endTime
                )
            }
            readingSessionDao.deleteSession(id)
            entity.toDomain()
        }

    override fun getBookmarksByBookId(bookId: String): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarksByBookId(bookId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeAllBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao.observeAllBookmarks().map { entities -> entities.map { it.toDomain() } }

    override fun observeBookmarksCreatedBetween(startMs: Long, endMs: Long): Flow<List<Bookmark>> =
        bookmarkDao.observeBookmarksCreatedBetween(startMs, endMs).map { entities -> entities.map { it.toDomain() } }

    override suspend fun insertBookmark(bookmark: Bookmark) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            rememberAnnotationTags(bookmark.tags, now)
            bookmarkDao.insertBookmark(
                bookmark.copy(
                    syncId = bookmark.syncId.ifBlank { UUID.randomUUID().toString() },
                    updatedAt = maxOf(bookmark.updatedAt, now)
                ).toEntity()
            )
        }
    }

    override suspend fun updateBookmark(bookmark: Bookmark) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            rememberAnnotationTags(bookmark.tags, now)
            bookmarkDao.updateBookmark(bookmark.copy(updatedAt = now).toEntity())
        }
    }

    override suspend fun updateBookmarkTags(bookmark: Bookmark, tags: List<String>) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            rememberAnnotationTags(bookmark.tags, now)
            rememberAnnotationTags(tags, now, revive = true)
            if (bookmark.id > 0L) bookmarkDao.updateTags(bookmark.id, AnnotationTags.encode(tags), now)
        }
    }

    override suspend fun deleteBookmark(bookmark: Bookmark) {
        val syncId = bookmark.syncId.ifBlank { "legacy-bookmark-${bookmark.id}" }
        syncStateDao.upsertTombstones(
            listOf(SyncTombstoneEntity("bookmark", syncId, System.currentTimeMillis(), syncIdentityStore.deviceId()))
        )
        bookmarkDao.deleteBookmark(bookmark.toEntity())
    }

    override suspend fun deleteAllBookmarksByBookId(bookId: String) {
        bookmarkDao.deleteAllBookmarksByBookId(bookId)
    }

    override fun getNotesByBookId(bookId: String): Flow<List<Note>> {
        return noteDao.getNotesByBookId(bookId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeTextNotes(): Flow<List<Note>> =
        noteDao.observeTextNotes().map { entities -> entities.map { it.toDomain() } }

    override fun observeTextNotesCreatedBetween(startMs: Long, endMs: Long): Flow<List<Note>> =
        noteDao.observeTextNotesCreatedBetween(startMs, endMs).map { entities -> entities.map { it.toDomain() } }

    override suspend fun insertNote(note: Note) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            rememberAnnotationTags(note.tags, now)
            noteDao.insertNote(
                note.copy(
                    syncId = note.syncId.ifBlank { UUID.randomUUID().toString() },
                    updatedAt = maxOf(note.updatedAt, now)
                ).toEntity()
            )
        }
    }

    override suspend fun updateNote(note: Note) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            rememberAnnotationTags(note.tags, now)
            noteDao.updateNote(note.copy(updatedAt = now).toEntity())
        }
    }

    override suspend fun updateNoteTags(note: Note, tags: List<String>) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            rememberAnnotationTags(note.tags, now)
            rememberAnnotationTags(tags, now, revive = true)
            if (note.id > 0L) noteDao.updateTags(note.id, AnnotationTags.encode(tags), now)
        }
    }

    override fun observeAnnotationTags(): Flow<List<String>> = combine(
        database.annotationTagDao().observeTags(), noteDao.observeTagLists(), bookmarkDao.observeTagLists()
    ) { catalog, notes, bookmarks ->
        val deleted = catalog.filter { it.isDeleted }.mapTo(mutableSetOf()) { it.name }
        (catalog.filterNot { it.isDeleted }.map { it.name } + (notes + bookmarks).flatMap(AnnotationTags::decode))
            .filterNot { it in deleted }.distinct().sorted()
    }

    override suspend fun createAnnotationTag(name: String) {
        database.withTransaction {
            rememberAnnotationTags(listOf(name), System.currentTimeMillis(), revive = true)
        }
    }

    private suspend fun rememberAnnotationTags(tags: List<String>, updatedAt: Long, revive: Boolean = false) {
        val existing = if (revive) database.annotationTagDao().getAllTags().associateBy { it.name } else emptyMap()
        val entries = AnnotationTags.normalize(tags).map { name ->
            AnnotationTagEntity(name, maxOf(updatedAt, (existing[name]?.updatedAt ?: 0L) + 1))
        }
        if (entries.isEmpty()) return
        if (revive) database.annotationTagDao().upsertTags(entries)
        else database.annotationTagDao().ensureTags(entries)
    }

    override suspend fun renameAnnotationTag(old: String, new: String) {
        val from = old.trim()
        val to = new.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) return
        database.withTransaction {
            val now = nextAnnotationTagTime(setOf(from, to))
            database.annotationTagDao().upsertTags(listOf(
                AnnotationTagEntity(from, now, isDeleted = true), AnnotationTagEntity(to, now)
            ))
            noteDao.getAllNotes().forEach { item ->
                val tags = AnnotationTags.decode(item.tagsJson)
                if (from in tags) noteDao.updateTags(item.id, AnnotationTags.encode(AnnotationTags.rename(tags, from, to)), now)
            }
            bookmarkDao.getAllBookmarks().forEach { item ->
                val tags = AnnotationTags.decode(item.tagsJson)
                if (from in tags) bookmarkDao.updateTags(item.id, AnnotationTags.encode(AnnotationTags.rename(tags, from, to)), now)
            }
        }
    }

    override suspend fun deleteAnnotationTag(name: String) {
        val target = name.trim()
        if (target.isEmpty()) return
        database.withTransaction {
            val now = nextAnnotationTagTime(setOf(target))
            database.annotationTagDao().upsertTags(listOf(AnnotationTagEntity(target, now, isDeleted = true)))
            noteDao.getAllNotes().forEach { item ->
                val tags = AnnotationTags.decode(item.tagsJson)
                if (target in tags) noteDao.updateTags(item.id, AnnotationTags.encode(tags - target), now)
            }
            bookmarkDao.getAllBookmarks().forEach { item ->
                val tags = AnnotationTags.decode(item.tagsJson)
                if (target in tags) bookmarkDao.updateTags(item.id, AnnotationTags.encode(tags - target), now)
            }
        }
    }

    override suspend fun deleteNote(note: Note) {
        val syncId = note.syncId.ifBlank { "legacy-note-${note.id}" }
        syncStateDao.upsertTombstones(
            listOf(SyncTombstoneEntity("note", syncId, System.currentTimeMillis(), syncIdentityStore.deviceId()))
        )
        noteDao.deleteNote(note.toEntity())
    }

    private suspend fun nextAnnotationTagTime(names: Set<String>): Long = maxOf(
        System.currentTimeMillis(),
        (database.annotationTagDao().getAllTags().filter { it.name in names }.maxOfOrNull { it.updatedAt } ?: 0L) + 1
    )

    override suspend fun applyAnnotationEdit(plan: AnnotationEditPlan) {
        val now = System.currentTimeMillis()
        val deviceId = syncIdentityStore.deviceId()
        database.withTransaction {
            val deleted = plan.deletes.filter { it.id > 0L && it.syncId.isNotBlank() }
            if (deleted.isNotEmpty()) {
                syncStateDao.upsertTombstones(
                    deleted.map { SyncTombstoneEntity("note", it.syncId, now, deviceId) }
                )
            }
            noteDao.applyAnnotationEdit(
                deleteIds = plan.deletes.map { it.id }.filter { it > 0L },
                updates = plan.updates.map { it.copy(updatedAt = now).toEntity() },
                inserts = plan.inserts.map {
                    it.copy(
                        id = 0,
                        syncId = UUID.randomUUID().toString(),
                        updatedAt = now
                    ).toEntity()
                }
            )
        }
    }

    override suspend fun applyTxtResegmentation(
        bookmarkUpdates: List<Bookmark>,
        noteUpdates: List<Note>,
        noteInserts: List<Note>
    ) {
        if (bookmarkUpdates.isEmpty() && noteUpdates.isEmpty() && noteInserts.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (bookmarkUpdates.isNotEmpty()) {
                bookmarkDao.updateBookmarks(bookmarkUpdates.map { it.copy(updatedAt = now).toEntity() })
            }
            if (noteUpdates.isNotEmpty()) noteDao.updateNotes(noteUpdates.map { it.copy(updatedAt = now).toEntity() })
            if (noteInserts.isNotEmpty()) {
                noteDao.insertNotes(noteInserts.map {
                    it.copy(id = 0L, syncId = UUID.randomUUID().toString(), updatedAt = now).toEntity()
                })
            }
        }
    }

    override suspend fun deleteAllNotesByBookId(bookId: String) {
        noteDao.deleteAllNotesByBookId(bookId)
    }

    override suspend fun deleteLegacyPdfAnnotationsByBookId(bookId: String) {
        noteDao.deleteLegacyPdfAnnotationsByBookId(bookId)
    }

    override fun getMostReadBooksBetween(startDate: String, endDate: String, limit: Int): Flow<List<BookDuration>> =
        readingRecordDao.getMostReadBooksBetween(startDate, endDate, limit)

    override fun getDailyTotalsBetween(startDate: String, endDate: String): Flow<List<DailyTotal>> {
        return readingRecordDao.getDailyTotalsBetween(startDate, endDate)
    }

    override fun getTotalDurationByBookId(bookId: String): Flow<Long?> =
        readingRecordDao.getTotalDurationByBookId(bookId)

    override fun getActiveDaysByBookId(bookId: String): Flow<Int> =
        readingRecordDao.getActiveDaysByBookId(bookId)

    private fun ReadingRecordEntity.toDomain(): ReadingRecord {
        return ReadingRecord(
            id = id,
            bookId = bookId,
            date = date,
            duration = duration,
            startTime = startTime,
            endTime = endTime,
            sourceDeviceId = sourceDeviceId,
            updatedAt = updatedAt
        )
    }

    private suspend fun applyDailyContributionDelta(
        bookId: String,
        deviceId: String,
        oldContributions: Map<String, ReadingSessionDayContribution>,
        newContributions: Map<String, ReadingSessionDayContribution>,
        session: ReadingSession
    ) {
        val dates = oldContributions.keys + newContributions.keys
        for (date in dates) {
            val oldDuration = oldContributions[date]?.duration ?: 0L
            val newContribution = newContributions[date]
            val newDuration = newContribution?.duration ?: 0L
            val delta = newDuration - oldDuration
            if (delta == 0L) continue
            adjustDailyRecord(
                bookId = bookId,
                date = date,
                deviceId = deviceId,
                delta = delta,
                startTime = newContribution?.startTime ?: session.startTime,
                endTime = newContribution?.endTime ?: session.endTime
            )
        }
    }

    private suspend fun adjustDailyRecord(
        bookId: String,
        date: String,
        deviceId: String,
        delta: Long,
        startTime: Long,
        endTime: Long
    ) {
        val existing = readingRecordDao.getRecordByBookAndDate(bookId, date, deviceId)
        if (delta > 0L) {
            if (existing != null) {
                readingRecordDao.updateRecordDuration(existing.id, delta, endTime)
            } else {
                readingRecordDao.insertRecord(
                    ReadingRecord(
                        bookId = bookId,
                        date = date,
                        duration = delta,
                        startTime = startTime,
                        endTime = maxOf(startTime, endTime),
                        sourceDeviceId = deviceId
                    ).toEntity()
                )
            }
        } else if (delta < 0L && existing != null) {
            readingRecordDao.adjustRecordDuration(existing.id, delta, System.currentTimeMillis())
        }
    }

    private fun ReadingSessionEntity.contributionMap(): Map<String, ReadingSessionDayContribution> {
        val decoded = decodeContributions(contributionsJson).mapValues { (day, duration) ->
            ReadingSessionDayContribution(date = day, duration = duration, startTime = startTime, endTime = endTime)
        }
        if (decoded.isNotEmpty()) return decoded
        if (contributedDuration <= 0L) return emptyMap()
        return mapOf(
            date to ReadingSessionDayContribution(
                date = date,
                duration = contributedDuration,
                startTime = startTime,
                endTime = endTime
            )
        )
    }

    private fun decodeContributions(raw: String): Map<String, Long> = runCatching {
        val json = JSONObject(raw)
        buildMap {
            for (key in json.keys()) {
                val value = json.optLong(key, 0L)
                if (value > 0L) put(key, value)
            }
        }
    }.getOrDefault(emptyMap())

    private fun encodeContributions(values: Map<String, Long>): String {
        if (values.isEmpty()) return "{}"
        return JSONObject(values.mapValues { it.value }).toString()
    }

    private fun ReadingSessionEntity.toDomain(): ReadingSession {
        val decoded = decodeContributions(contributionsJson)
        val contributions = if (decoded.isEmpty() && contributedDuration > 0L) {
            mapOf(date to contributedDuration)
        } else {
            decoded
        }
        return ReadingSession(
            id = id,
            sessionKey = sessionKey,
            bookId = bookId,
            date = date,
            startTime = startTime,
            endTime = endTime,
            duration = duration,
            startChapterIndex = startChapterIndex,
            endChapterIndex = endChapterIndex,
            startChapterTitle = startChapterTitle,
            endChapterTitle = endChapterTitle,
            startProgress = startProgress,
            endProgress = endProgress,
            mode = mode,
            sourceDeviceId = sourceDeviceId,
            note = note,
            mood = mood,
            dailyContributions = contributions,
            contributedDuration = if (contributedDuration > 0L) contributedDuration else contributions.values.sum(),
            updatedAt = updatedAt
        )
    }

    private fun ReadingSession.toEntity(): ReadingSessionEntity = ReadingSessionEntity(
        id = id,
        bookId = bookId,
        date = date,
        startTime = startTime,
        endTime = endTime,
        duration = duration,
        startChapterIndex = startChapterIndex,
        endChapterIndex = endChapterIndex,
        startChapterTitle = startChapterTitle,
        endChapterTitle = endChapterTitle,
        startProgress = startProgress,
        endProgress = endProgress,
        mode = mode,
        sourceDeviceId = sourceDeviceId,
        note = note,
        mood = mood,
        sessionKey = sessionKey,
        contributedDuration = contributedDuration,
        contributionsJson = encodeContributions(dailyContributions),
        updatedAt = updatedAt
    )

    private fun ReadingRecord.toEntity(): ReadingRecordEntity {
        return ReadingRecordEntity(
            id = id,
            bookId = bookId,
            date = date,
            duration = duration,
            startTime = startTime,
            endTime = endTime,
            sourceDeviceId = sourceDeviceId,
            updatedAt = updatedAt
        )
    }

    private fun BookmarkEntity.toDomain(): Bookmark {
        return Bookmark(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            position = position,
            locatorJson = locatorJson,
            title = title,
            createdAt = createdAt,
            syncId = syncId,
            updatedAt = updatedAt,
            remark = remark,
            tags = AnnotationTags.decode(tagsJson),
            isStale = isStale
        )
    }

    private fun Bookmark.toEntity(): BookmarkEntity {
        return BookmarkEntity(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            position = position,
            locatorJson = locatorJson,
            title = title,
            createdAt = createdAt,
            syncId = syncId,
            updatedAt = updatedAt,
            remark = remark,
            tagsJson = AnnotationTags.encode(tags),
            isStale = isStale
        )
    }

    private fun NoteEntity.toDomain(): Note {
        return Note(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            startPosition = startPosition,
            endPosition = endPosition,
            startLocatorJson = startLocatorJson,
            endLocatorJson = endLocatorJson,
            selectedText = selectedText,
            note = note,
            color = color,
            createdAt = createdAt,
            type = type,
            syncId = syncId,
            updatedAt = updatedAt,
            isNote = isNote,
            origin = origin,
            sourceRuleId = sourceRuleId,
            sourceMatchKey = sourceMatchKey,
            styleSnapshotJson = styleSnapshotJson,
            tags = AnnotationTags.decode(tagsJson),
            isStale = isStale
        )
    }

    private fun Note.toEntity(): NoteEntity {
        return NoteEntity(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            startPosition = startPosition,
            endPosition = endPosition,
            startLocatorJson = startLocatorJson,
            endLocatorJson = endLocatorJson,
            selectedText = selectedText,
            note = note,
            color = color,
            createdAt = createdAt,
            type = type,
            syncId = syncId,
            updatedAt = updatedAt,
            isNote = isNote,
            origin = origin,
            sourceRuleId = sourceRuleId,
            sourceMatchKey = sourceMatchKey,
            styleSnapshotJson = styleSnapshotJson,
            tagsJson = AnnotationTags.encode(tags),
            isStale = isStale
        )
    }
}
