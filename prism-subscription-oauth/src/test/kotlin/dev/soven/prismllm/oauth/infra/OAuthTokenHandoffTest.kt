package dev.soven.prismllm.oauth.infra

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class OAuthTokenHandoffTest {
    @Test
    fun `complete response survives cancellation before the continuation is dispatched`() = runTest {
        val future = CompletableFuture<String>()
        var received: String? = null
        val caller = async {
            received = awaitTokenResponse(future, 60_000)
        }
        runCurrent()
        assertTrue(future.complete("synthetic-rotated-token"))
        caller.cancel()
        runCurrent()
        caller.join()
        assertEquals("synthetic-rotated-token", received)
        assertFalse(future.isCancelled)
    }

    @Test
    fun `complete response wins a virtual deadline before coroutine dispatch`() = runTest {
        val future = CompletableFuture<String>()
        var received: String? = null
        val caller = async {
            withTimeoutOrNull(1_000) {
                received = awaitTokenResponse(future, 60_000)
            }
        }
        runCurrent()
        testScheduler.advanceTimeBy(1_000)
        assertTrue(future.complete("synthetic-complete-response"))
        runCurrent()
        caller.join()
        assertEquals("synthetic-complete-response", received)
    }

    @Test
    fun `cancellation before the full body cancels HTTP and rejects late completion`() = runTest {
        val future = CompletableFuture<String>()
        var received: String? = null
        val caller = async { received = awaitTokenResponse(future, 60_000) }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(future.isCancelled)
        assertFalse(future.complete("late-synthetic-response"))
        assertNull(received)
    }

    @Test
    fun realDeadlineCancelsPendingResponseAndReportsTimeout() = runBlocking {
        val future = CompletableFuture<String>()
        val failure = withTimeout(2_000) { runCatching { awaitTokenResponse(future, 50) }.exceptionOrNull() }
        assertEquals("timeout", assertIs<OAuthRefreshException>(failure).errorCode)
        assertTrue(future.isCancelled)
    }
}
