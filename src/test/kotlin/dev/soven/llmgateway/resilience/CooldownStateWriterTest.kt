package dev.soven.llmgateway.resilience

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CooldownStateWriterTest {
    private class FakeStore(private val deleteThrows: Boolean = false) : CooldownStateStore {
        val deletes = mutableListOf<String>()
        val upserts = mutableListOf<String>()

        override suspend fun upsert(snapshot: CooldownStateSnapshot) {
            upserts += snapshot.stateKey
        }

        override suspend fun delete(stateKey: String) {
            deletes += stateKey
            if (deleteThrows) throw IllegalStateException("database unavailable")
        }
    }

    private fun withWriter(
        store: CooldownStateStore,
        block: suspend (CooldownStateWriter) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob())
        val writer = CooldownStateWriter(store, scope)
        runBlocking { block(writer) }
        scope.cancel()
    }

    @Test
    fun `awaitRemovals reports success after matching deletes complete`() {
        val store = FakeStore()
        withWriter(store) { writer ->
            writer.submit(CooldownChange.Updated("ch:1", 1, 100L, "transient"))
            writer.submit(CooldownChange.Removed("ch:1"))
            writer.submit(CooldownChange.Removed("key:1"))

            val result = writer.awaitRemovals(setOf("ch:1", "key:1"), 1_000)

            assertTrue(result)
            assertTrue(store.deletes.containsAll(setOf("ch:1", "key:1")))
        }
    }

    @Test
    fun `awaitRemovals reports failure when a matching delete fails`() {
        val store = FakeStore(deleteThrows = true)
        withWriter(store) { writer ->
            writer.submit(CooldownChange.Removed("ch:1"))

            val result = writer.awaitRemovals(setOf("ch:1"), 1_000)

            assertFalse(result)
        }
    }

    @Test
    fun `awaitRemovals ignores failures from unrelated keys`() {
        val store = FakeStore(deleteThrows = true)
        withWriter(store) { writer ->
            writer.submit(CooldownChange.Removed("other"))

            val result = writer.awaitRemovals(emptySet(), 1_000)

            assertTrue(result)
        }
    }
}
