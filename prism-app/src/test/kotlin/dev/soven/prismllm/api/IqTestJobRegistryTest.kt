package dev.soven.prismllm.api

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("unit")
class IqTestJobRegistryTest {
    @Test
    fun `completed job carries run id`() {
        val registry = IqTestJobRegistry()
        val jobId = registry.create()

        assertEquals(IqTestJobStatus.RUNNING, registry.find(jobId)?.status)

        registry.complete(jobId, 42)

        val job = assertNotNull(registry.find(jobId))
        assertEquals(IqTestJobStatus.COMPLETED, job.status)
        assertEquals(42L, job.runId)
        assertNull(job.error)
    }

    @Test
    fun `failed job carries bounded error`() {
        val registry = IqTestJobRegistry()
        val jobId = registry.create()

        registry.fail(jobId, "上游超时")

        val job = assertNotNull(registry.find(jobId))
        assertEquals(IqTestJobStatus.FAILED, job.status)
        assertNull(job.runId)
        assertEquals("上游超时", job.error)
    }

    @Test
    fun `unknown job is not found`() {
        assertNull(IqTestJobRegistry().find("missing"))
    }

    @Test
    fun `expired jobs are pruned`() {
        var now = 1_000L
        val registry = IqTestJobRegistry(maxAgeMs = 100, now = { now })
        val jobId = registry.create()

        now = 1_101L

        assertNull(registry.find(jobId))
    }
}
