package dev.soven.llmgateway.resilience

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CooldownStateWriterTest {
    private class FakeStore(
        private val deleteThrows: Boolean = false,
        private val upsertStarted: CompletableDeferred<Unit>? = null,
        private val upsertGate: CompletableDeferred<Unit>? = null,
    ) : CooldownStateStore {
        val deletes = CopyOnWriteArrayList<String>()
        val upserts = CopyOnWriteArrayList<String>()

        override suspend fun upsert(snapshot: CooldownStateSnapshot) {
            upsertStarted?.complete(Unit)
            upsertGate?.await()
            upserts += snapshot.stateKey
        }

        override suspend fun delete(stateKey: String) {
            deletes += stateKey
            if (deleteThrows) throw IllegalStateException("database unavailable")
        }
    }

    @Test
    fun `deleteNow deletes target keys and reports success`() = runTest {
        val store = FakeStore()
        val writer = CooldownStateWriter(store, backgroundScope)

        assertTrue(writer.deleteNow(setOf("ch:1", "key:1"), writer.currentSeq(), 1_000))

        assertEquals(setOf("ch:1", "key:1"), store.deletes.toSet())
    }

    @Test
    fun `deleteNow reports failure when a target delete fails`() = runTest {
        val store = FakeStore(deleteThrows = true)
        val writer = CooldownStateWriter(store, backgroundScope)

        assertFalse(writer.deleteNow(setOf("ch:1"), writer.currentSeq(), 1_000))
    }

    @Test
    fun `deleteNow keeps a newer pending update instead of deleting it`() = runTest {
        val store = FakeStore()
        val writer = CooldownStateWriter(store, backgroundScope)
        val baseline = writer.currentSeq()
        writer.submit(CooldownChange.Removed("ch:1"))
        writer.submit(CooldownChange.Updated("ch:1", 1, 100L, "transient"))

        assertTrue(writer.deleteNow(setOf("ch:1"), baseline, 1_000))
        assertFalse(store.deletes.contains("ch:1"))

        writer.closeAndDrain(1_000)
        assertEquals(listOf("ch:1"), store.upserts)
    }

    @Test
    fun `deleteNow waits for an in-flight update and keeps it`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(upsertStarted = started, upsertGate = gate)
        val writer = CooldownStateWriter(store, backgroundScope)
        val baseline = writer.currentSeq()
        writer.submit(CooldownChange.Removed("ch:1"))
        writer.submit(CooldownChange.Updated("ch:1", 1, 100L, "transient"))
        started.await()

        val deletion = async { writer.deleteNow(setOf("ch:1"), baseline, 2_000) }
        delay(100)
        gate.complete(Unit)

        assertTrue(deletion.await())
        assertFalse(store.deletes.contains("ch:1"))
    }

    @Test
    fun `writes submitted after a delete are still persisted`() = runTest {
        val store = FakeStore()
        val writer = CooldownStateWriter(store, backgroundScope)
        val baseline = writer.currentSeq()
        writer.submit(CooldownChange.Removed("ch:1"))

        assertTrue(writer.deleteNow(setOf("ch:1"), baseline, 1_000))
        writer.submit(CooldownChange.Updated("ch:1", 1, 100L, "transient"))
        writer.closeAndDrain(1_000)

        assertEquals(listOf("ch:1"), store.deletes)
        assertEquals(listOf("ch:1"), store.upserts)
    }

    @Test
    fun `deleteNow does not wait for unrelated slow writes`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(upsertStarted = started, upsertGate = gate)
        val scope = CoroutineScope(SupervisorJob())
        val writer = CooldownStateWriter(store, scope)
        try {
            writer.submit(CooldownChange.Updated("other", 1, 100L, "transient"))
            started.await()

            val elapsed = measureTimeMillis {
                assertTrue(writer.deleteNow(setOf("ch:1"), writer.currentSeq(), 2_000))
            }

            assertTrue(elapsed < 1_000, "deleteNow waited on an unrelated key for ${elapsed}ms")
            assertEquals(listOf("ch:1"), store.deletes)
        } finally {
            gate.complete(Unit)
            writer.closeAndDrain(2_000)
            scope.cancel()
        }
    }

    @Test
    fun `deleteNow delegates to deleteBatch when multiple keys are deleted`() = runTest {
        val batchCalls = CopyOnWriteArrayList<List<String>>()
        val store = object : CooldownStateStore {
            override suspend fun upsert(snapshot: CooldownStateSnapshot) {}
            override suspend fun delete(stateKey: String) {}
            override suspend fun deleteBatch(stateKeys: Collection<String>) {
                batchCalls += stateKeys.toList()
            }
        }
        val writer = CooldownStateWriter(store, backgroundScope)
        assertTrue(writer.deleteNow(setOf("k1", "k2", "k3"), writer.currentSeq(), 1_000))
        assertEquals(1, batchCalls.size)
        assertEquals(setOf("k1", "k2", "k3"), batchCalls[0].toSet())
    }
}
