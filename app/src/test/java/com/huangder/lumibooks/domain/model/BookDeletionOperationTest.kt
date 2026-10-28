package com.huangder.lumibooks.domain.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookDeletionOperationTest {
    @Test fun localOnlyNeverInvokesCloudDeletion() = runBlocking {
        val calls = mutableListOf<String>()
        assertTrue(executeBookDeletion(BookDeleteMode.LOCAL_ONLY, { calls += "local"; emptyList() }, { calls += "cloud"; emptyList() }).isEmpty())
        assertEquals(listOf("local"), calls)
    }
    @Test fun normalCloudFailureKeepsTheLocalCopy() = runBlocking {
        val calls = mutableListOf<String>()
        val errors = executeBookDeletion(BookDeleteMode.LOCAL_AND_CLOUD, { calls += "local"; emptyList() }, { calls += "cloud"; listOf("conflict") })
        assertEquals(listOf("cloud"), calls); assertEquals(listOf("conflict"), errors)
    }
    @Test fun normalDeletionRemovesCloudBeforeLocal() = runBlocking {
        val calls = mutableListOf<String>()
        executeBookDeletion(BookDeleteMode.LOCAL_AND_CLOUD, { calls += "local"; emptyList() }, { calls += "cloud"; emptyList() })
        assertEquals(listOf("cloud", "local"), calls)
    }
    @Test fun forcedDeletionKeepsLocalCompletionWhenCloudFails() = runBlocking {
        val calls = mutableListOf<String>()
        val errors = executeBookDeletion(BookDeleteMode.FORCE_LOCAL_AND_CLOUD, { calls += "local"; emptyList() }, { calls += "cloud"; listOf("offline") })
        assertEquals(listOf("local", "cloud"), calls); assertEquals(listOf("offline"), errors)
    }
    @Test fun forcedDeletionStillAttemptsCloudAndReportsLocalCleanupFailures() = runBlocking {
        val errors = executeBookDeletion(BookDeleteMode.FORCE_LOCAL_AND_CLOUD, { listOf("file cleanup failed") }, { listOf("cloud unavailable") })
        assertEquals(listOf("file cleanup failed", "cloud unavailable"), errors)
    }
}
