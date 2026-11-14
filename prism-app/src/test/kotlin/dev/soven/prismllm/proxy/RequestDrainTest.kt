package dev.soven.prismllm.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("unit")
class RequestDrainTest {
    @Test
    fun `shutdown rejects new admission and waits until finalization releases the lease`() = runTest {
        val drain = RequestDrain()
        val first = assertNotNull(drain.acquire(Job()))
        drain.beginShutdown()
        assertTrue(drain.shuttingDown)
        assertNull(drain.acquire(Job()))
        assertFalse(drain.awaitDrained(1))
        first.close()
        assertTrue(drain.awaitDrained(1))
    }

    @Test
    fun `duplicate release cannot remove another request and shutdown without requests is immediate`() = runTest {
        val drain = RequestDrain()
        val first = assertNotNull(drain.acquire(Job()))
        val second = assertNotNull(drain.acquire(Job()))
        drain.beginShutdown()
        first.close()
        first.close()
        assertFalse(drain.awaitDrained(1))
        second.close()
        assertTrue(drain.awaitDrained(1))
        val empty = RequestDrain()
        empty.beginShutdown()
        assertTrue(empty.awaitDrained(0))
    }

    @Test
    fun `timeout cancellation still waits for non cancellable request finalization`() = runTest {
        val drain = RequestDrain()
        val entered = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val serving = launch {
            val lease = assertNotNull(drain.acquire(assertNotNull(currentCoroutineContext()[Job])))
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finalizing.complete(Unit)
                    finish.await()
                }
                lease.close()
            }
        }
        entered.await()
        drain.beginShutdown()
        drain.cancelRemaining()
        finalizing.await()
        assertTrue(serving.isCancelled)
        assertFalse(drain.awaitDrained(1))
        finish.complete(Unit)
        serving.join()
        assertTrue(drain.awaitDrained(1))
    }

    @Test
    fun `normal drain waits for both finalization and response write in either order`() = runTest {
        repeat(2) { responseFirst ->
            val drain = RequestDrain()
            val lease = assertNotNull(drain.acquire(Job()))
            lease.awaitResponse()
            drain.beginShutdown()
            if (responseFirst == 1) lease.responseCompleted() else lease.close()
            assertFalse(drain.awaitDrained(1))
            if (responseFirst == 1) lease.close() else lease.responseCompleted()
            assertTrue(drain.awaitDrained(1))
            lease.close()
            lease.responseCompleted()
            assertTrue(drain.awaitFinalized(1))
        }
    }

    @Test
    fun `timeout finalization budget does not wait for pending downstream output`() = runTest {
        val drain = RequestDrain()
        val job = Job()
        val lease = assertNotNull(drain.acquire(job))
        lease.awaitResponse()
        drain.beginShutdown()
        lease.close()
        drain.cancelRemaining()
        assertFalse(job.isCancelled)
        assertTrue(drain.awaitFinalized(0))
        assertFalse(drain.awaitDrained(1))
        lease.responseCompleted()
        assertTrue(drain.awaitDrained(0))
    }

    @Test
    fun `concurrent shutdown cannot miss an admitted request`() = runTest {
        repeat(100) {
            val drain = RequestDrain()
            val lease = coroutineScope {
                val admitted = async(Dispatchers.Default) { drain.acquire(Job()) }
                val stopping = async(Dispatchers.Default) { drain.beginShutdown() }
                stopping.await()
                admitted.await()
            }
            assertNull(drain.acquire(Job()))
            if (lease != null) {
                assertFalse(drain.awaitDrained(1))
                lease.close()
            }
            assertTrue(drain.awaitDrained(1))
        }
    }
}
