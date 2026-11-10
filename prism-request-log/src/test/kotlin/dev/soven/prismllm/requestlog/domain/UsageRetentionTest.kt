package dev.soven.prismllm.requestlog.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsageRetentionTest {
    @Test
    fun `retention cutoff uses exact days`() {
        assertEquals(86_400_000L * 20_000, retentionCutoff(86_400_000L * 20_030, 30))
    }

    @Test
    fun `retention policy accepts default and boundary values`() {
        assertEquals(30, RetentionPolicy().retentionDays)
        assertEquals(1, RetentionPolicy(retentionDays = 1).retentionDays)
        assertEquals(3_650, RetentionPolicy(retentionDays = 3_650).retentionDays)
    }

    @Test
    fun `retention policy rejects unsafe values`() {
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(retentionDays = 0) }
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(batchSize = 99) }
        assertFailsWith<IllegalArgumentException> { RetentionPolicy(cleanupIntervalMs = 59_999) }
    }
}
