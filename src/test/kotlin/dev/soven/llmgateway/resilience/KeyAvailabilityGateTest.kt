package dev.soven.llmgateway.resilience

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyAvailabilityGateTest {
    @Test
    fun `writer waits for active reader and blocks new readers`() = runBlocking {
        val gate = KeyAvailabilityGate()
        val readerStarted = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val lateReaderStarted = CompletableDeferred<Unit>()

        val reader = launch {
            gate.withReader("key-1") {
                readerStarted.complete(Unit)
                releaseReader.await()
            }
        }
        readerStarted.await()

        val writer = launch {
            gate.withWriter("key-1") {
                writerStarted.complete(Unit)
                releaseWriter.await()
            }
        }
        delay(50)
        assertFalse(writerStarted.isCompleted)

        val lateReader = launch {
            gate.withReader("key-1") { lateReaderStarted.complete(Unit) }
        }
        delay(50)
        assertFalse(lateReaderStarted.isCompleted)

        releaseReader.complete(Unit)
        withTimeout(1_000) { writerStarted.await() }
        assertFalse(lateReaderStarted.isCompleted)

        releaseWriter.complete(Unit)
        withTimeout(1_000) { lateReaderStarted.await() }
        reader.join()
        writer.join()
        lateReader.join()
    }

    @Test
    fun `cancelled reader releases its slot for the next writer`() = runBlocking {
        val gate = KeyAvailabilityGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val writerStarted = CompletableDeferred<Unit>()

        val first = launch {
            gate.withReader("key-3") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val second = launch {
            gate.withReader("key-3") {
                secondStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        secondStarted.await()
        second.cancelAndJoin()

        val writer = launch {
            gate.withWriter("key-3") { writerStarted.complete(Unit) }
        }
        delay(50)
        assertFalse(writerStarted.isCompleted)
        releaseFirst.complete(Unit)
        withTimeout(1_000) { writerStarted.await() }
        first.join()
        writer.join()
    }

    @Test
    fun `cancelled waiting writer does not block future readers`() = runBlocking {
        val gate = KeyAvailabilityGate()
        val readerStarted = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()

        val reader = launch {
            gate.withReader("key-2") {
                readerStarted.complete(Unit)
                releaseReader.await()
            }
        }
        readerStarted.await()

        val writer = launch { gate.withWriter("key-2") { error("writer should be cancelled before entering") } }
        delay(50)
        writer.cancelAndJoin()
        releaseReader.complete(Unit)
        reader.join()

        var acquired = false
        withTimeout(1_000) {
            gate.withReader("key-2") { acquired = true }
        }
        assertTrue(acquired)
    }
}
