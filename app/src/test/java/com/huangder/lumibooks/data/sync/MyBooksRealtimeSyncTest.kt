package com.huangder.lumibooks.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MyBooksRealtimeSyncTest {
    @Test fun startupQueuesOnlyOneFullSyncAndNeverPolls() = runTest {
        var full = 0; var reading = 0
        val loop = MyBooksSyncEventLoop(backgroundScope, { true }, { reading++ }, { full++ }, { throw it })
        loop.start(MutableStateFlow(emptyMap())); loop.start(MutableStateFlow(emptyMap())); runCurrent()
        assertEquals(1, full)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(1, full); assertEquals(0, reading)
    }
    @Test fun readingUsesTheSameFiveSecondDebounceAsWebdav() = runTest {
        val changes = MutableStateFlow(emptyMap<String,String>()); var reading = 0
        val loop = MyBooksSyncEventLoop(backgroundScope, { true }, { reading++ }, {}, { throw it })
        loop.start(changes); runCurrent()
        changes.value = mapOf("book" to "first"); runCurrent(); advanceTimeBy(4_000)
        changes.value = mapOf("book" to "latest"); runCurrent(); advanceTimeBy(4_999); runCurrent()
        assertEquals(0, reading)
        advanceTimeBy(2); runCurrent(); assertEquals(1, reading)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, reading)
    }
    @Test fun backgroundingQueuesAFullSyncInsteadOfStartingAPollingLoop() = runTest {
        var full = 0; var reading = 0
        val loop = MyBooksSyncEventLoop(backgroundScope, { true }, { reading++ }, { full++ }, { throw it })
        loop.start(MutableStateFlow(emptyMap())); runCurrent(); loop.onAppBackgrounded(); runCurrent()
        assertEquals(2, full); assertEquals(0, reading)
        advanceTimeBy(60_000); runCurrent(); assertEquals(2, full)
    }
    @Test fun changingModeAloneDoesNotStartSyncAndManualEventsDoNotConnect() = runTest {
        var automatic = false; var full = 0; var reading = 0
        val changes = MutableStateFlow(emptyMap<String,String>())
        val loop = MyBooksSyncEventLoop(backgroundScope, { automatic }, { reading++ }, { full++ }, { throw it })
        loop.start(changes); runCurrent(); loop.onAppBackgrounded(); changes.value = mapOf("book" to "edit")
        runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(0, full); assertEquals(0, reading)
        automatic = true; advanceTimeBy(60_000); runCurrent()
        assertEquals(0, full); assertEquals(0, reading)
        loop.onAppBackgrounded(); runCurrent(); assertEquals(1, full)
    }
    @Test fun switchingToManualDuringDebouncePreventsThePendingProgressUpload() = runTest {
        var automatic = true; var reading = 0
        val changes = MutableStateFlow(emptyMap<String,String>())
        val loop = MyBooksSyncEventLoop(backgroundScope, { automatic }, { reading++ }, {}, { throw it })
        loop.start(changes); runCurrent(); changes.value = mapOf("book" to "edit"); runCurrent()
        advanceTimeBy(2_000); automatic = false; advanceTimeBy(5_000); runCurrent()
        assertEquals(0, reading)
    }
    @Test fun readerExitFinishesTheDatabaseWriteBeforeQueueingFullSyncEvenAfterTheScreenCloses() = runTest {
        val saved = CompletableDeferred<Unit>(); val screenJob = Job(); val screen = CoroutineScope(coroutineContext + screenJob)
        var position = 10; var queuedPosition = 0
        val loop = MyBooksSyncEventLoop(backgroundScope, { true }, {}, { queuedPosition = position }, { throw it })
        loop.start(MutableStateFlow(emptyMap())); runCurrent(); queuedPosition = 0
        screen.launch { loop.persistThenUpload { saved.await(); position = 80 } }; runCurrent(); screenJob.cancel()
        assertEquals(0, queuedPosition); saved.complete(Unit); runCurrent(); assertEquals(80, queuedPosition)
    }
    @Test fun progressFailureDoesNotCreateExtraFullSyncRulesOrKillTheNextEdit() = runTest {
        var reading = 0; var full = 0; var errors = 0
        val changes = MutableStateFlow(emptyMap<String,String>())
        val loop = MyBooksSyncEventLoop(backgroundScope, { true }, { reading++; if (reading == 1) throw IOException("offline") }, { full++ }, { errors++ })
        loop.start(changes); runCurrent(); changes.value = mapOf("book" to "first"); runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(1, errors); assertEquals(1, full)
        changes.value = mapOf("book" to "next"); runCurrent(); advanceTimeBy(5_001); runCurrent(); assertEquals(2, reading)
    }
    @Test fun failedFinalPersistenceDoesNotQueueAnOlderPosition() = runTest {
        var full = 0; var errors = 0
        val loop = MyBooksSyncEventLoop(backgroundScope, { true }, {}, { full++ }, { errors++ })
        loop.start(MutableStateFlow(emptyMap())); runCurrent(); full = 0
        loop.persistThenUpload { throw IOException("database write failed") }; runCurrent()
        assertEquals(0, full); assertEquals(1, errors)
    }
}
