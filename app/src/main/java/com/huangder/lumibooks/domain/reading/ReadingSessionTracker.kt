package com.huangder.lumibooks.domain.reading

import com.huangder.lumibooks.domain.model.MIN_READING_SESSION_MS
import com.huangder.lumibooks.domain.model.READING_SESSION_RESUME_GRACE_MS
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ReadingSessionSnapshot(
    val sessionKey: String,
    val bookId: String,
    val format: String,
    val startWallTime: Long,
    val endWallTime: Long,
    val duration: Long
) {
    val date: String
        get() = Instant.ofEpochMilli(startWallTime)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
}

data class ReadingTrackerResume(
    val snapshot: ReadingSessionSnapshot?,
    val previous: ReadingSessionSnapshot?,
    val continued: Boolean
)

/**
 * Pure session timing. Monotonic time drives duration; wall time only labels the session.
 * Pauses are excluded and short background visits keep the same session identity.
 */
class ReadingSessionTracker(
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val newSessionKey: () -> String = { UUID.randomUUID().toString() },
    private val minRawDurationMs: Long = MIN_READING_SESSION_MS,
    private val resumeGraceMs: Long = READING_SESSION_RESUME_GRACE_MS
) {
    private var sessionKey: String? = null
    private var bookId: String? = null
    private var format: String? = null
    private var startWallTime: Long = 0L
    private var accumulatedMs: Long = 0L
    private var runningSinceMonotonic: Long? = null
    private var pausedAtWallTime: Long = 0L
    private var pausedAtMonotonic: Long = 0L

    val isActive: Boolean get() = sessionKey != null
    val isPaused: Boolean get() = isActive && runningSinceMonotonic == null

    /** Continue the current session; return a session that must be saved when the book changed. */
    fun begin(bookId: String, format: String): ReadingSessionSnapshot? {
        if (isActive) {
            if (this.bookId == bookId && this.format == format) return null
            val previous = finish()
            startNew(bookId, format)
            return previous
        }
        startNew(bookId, format)
        return null
    }

    fun pause(): ReadingSessionSnapshot? {
        if (!isActive) return null
        stopRunning()
        pausedAtWallTime = wallClock()
        pausedAtMonotonic = monotonicClock()
        return snapshotInternal()
    }

    fun resume(bookId: String, format: String): ReadingTrackerResume {
        if (!isActive) {
            startNew(bookId, format)
            return ReadingTrackerResume(snapshot = snapshotInternal(), previous = null, continued = false)
        }
        if (this.bookId != bookId || this.format != format) {
            val previous = finish()
            startNew(bookId, format)
            return ReadingTrackerResume(snapshot = snapshotInternal(), previous = previous, continued = false)
        }
        val nowMonotonic = monotonicClock()
        if (runningSinceMonotonic != null) {
            return ReadingTrackerResume(snapshot = snapshotInternal(), previous = null, continued = true)
        }
        val pausedGapMs = (wallClock() - pausedAtWallTime).coerceAtLeast(0L)
        val monotonicGapMs = (nowMonotonic - pausedAtMonotonic).coerceAtLeast(0L)
        if (pausedGapMs <= resumeGraceMs && monotonicGapMs <= resumeGraceMs) {
            runningSinceMonotonic = nowMonotonic
            pausedAtWallTime = 0L
            pausedAtMonotonic = 0L
            return ReadingTrackerResume(snapshot = snapshotInternal(), previous = null, continued = true)
        }
        val previous = finish()
        startNew(bookId, format)
        return ReadingTrackerResume(snapshot = snapshotInternal(), previous = previous, continued = false)
    }

    fun snapshot(): ReadingSessionSnapshot? = snapshotInternal()

    /** Ends the session exactly once. Repeated calls return null. */
    fun finish(): ReadingSessionSnapshot? {
        if (!isActive) return null
        stopRunning()
        val finished = snapshotInternal()
        sessionKey = null
        bookId = null
        format = null
        accumulatedMs = 0L
        runningSinceMonotonic = null
        pausedAtWallTime = 0L
        pausedAtMonotonic = 0L
        return finished?.takeIf { it.duration >= minRawDurationMs }
    }

    fun reset() {
        sessionKey = null
        bookId = null
        format = null
        accumulatedMs = 0L
        runningSinceMonotonic = null
        pausedAtWallTime = 0L
        pausedAtMonotonic = 0L
    }

    private fun startNew(bookId: String, format: String) {
        sessionKey = newSessionKey()
        this.bookId = bookId
        this.format = format
        startWallTime = wallClock()
        accumulatedMs = 0L
        runningSinceMonotonic = monotonicClock()
        pausedAtWallTime = 0L
        pausedAtMonotonic = 0L
    }

    private fun stopRunning() {
        val since = runningSinceMonotonic ?: return
        val now = monotonicClock()
        accumulatedMs += (now - since).coerceAtLeast(0L)
        runningSinceMonotonic = null
    }

    private fun snapshotInternal(): ReadingSessionSnapshot? {
        val key = sessionKey ?: return null
        val owner = bookId ?: return null
        val formatName = format ?: return null
        val running = runningSinceMonotonic
        val duration = if (running != null) {
            accumulatedMs + (monotonicClock() - running).coerceAtLeast(0L)
        } else {
            accumulatedMs
        }
        val endWall = if (running != null) wallClock() else pausedAtWallTime.takeIf { it > 0L } ?: wallClock()
        return ReadingSessionSnapshot(
            sessionKey = key,
            bookId = owner,
            format = formatName,
            startWallTime = startWallTime,
            endWallTime = maxOf(startWallTime, endWall),
            duration = duration
        )
    }
}
