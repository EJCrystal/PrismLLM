package dev.soven.prismllm.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminSessionsTest {
    @Test
    fun `username lock serializes password changes and session issuance`() = runBlocking {
        val sessions = AdminSessions()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            sessions.withUsernameLock("admin") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            sessions.withUsernameLock("admin") { secondEntered.complete(Unit) }
        }

        yield()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }
}
