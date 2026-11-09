package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.domain.OAuthStoredWindowState
import dev.soven.llmgateway.domain.OAuthWindowSnapshot
import dev.soven.llmgateway.repo.OAuthWindowStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthWindowStateWriterTest {
    @Test
    fun `coalesces pending snapshots per account`() = runTest {
        val store = RecordingStore()
        val writer = OAuthWindowStateWriter(store, backgroundScope)
        writer.submit(snapshot("account-a", listOf(1)))
        writer.submit(snapshot("account-a", listOf(1, 2)))
        writer.closeAndDrain(5_000)

        assertEquals(1, store.writes.size)
        assertEquals(listOf(1L, 2L), store.writes.single().hits)
    }

    @Test
    fun `flushes every account on close`() = runTest {
        val store = RecordingStore()
        val writer = OAuthWindowStateWriter(store, backgroundScope)
        writer.submit(snapshot("account-a", listOf(1)))
        writer.submit(snapshot("account-b", listOf(2)))
        writer.closeAndDrain(5_000)

        assertEquals(setOf("account-a", "account-b"), store.writes.map { it.accountId }.toSet())
    }

    @Test
    fun `a failed write does not block other accounts`() = runTest {
        val store = RecordingStore(failKeys = setOf("account-a"))
        val writer = OAuthWindowStateWriter(store, backgroundScope)
        writer.submit(snapshot("account-a", listOf(1)))
        writer.submit(snapshot("account-b", listOf(2)))
        writer.closeAndDrain(5_000)

        assertEquals(listOf("account-b"), store.writes.map { it.accountId })
    }

    private fun snapshot(accountId: String, hits: List<Long>) = OAuthWindowSnapshot(
        accountId = accountId,
        hits = hits,
        updatedAt = 1,
    )

    private class RecordingStore(private val failKeys: Set<String> = emptySet()) : OAuthWindowStore {
        val writes = mutableListOf<OAuthWindowSnapshot>()

        override suspend fun loadAll(limit: Int): List<OAuthStoredWindowState> = emptyList()

        override suspend fun upsert(snapshot: OAuthWindowSnapshot) {
            if (snapshot.accountId in failKeys) error("write failed")
            writes += snapshot
        }
    }
}
