package dev.soven.prismllm.integration

import dev.soven.prismllm.admin.persistence.IqTestJobRepository
import dev.soven.prismllm.admin.persistence.NewIqTestJob
import dev.soven.prismllm.api.IqTestJobRegistry
import dev.soven.prismllm.api.IqTestJobStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
@Tag("shard-3")
class IqTestJobPersistenceIntegrationTest : GatewayIntegrationTestBase() {
    @Test
    fun `persisted running job is recovered on application restart`() = runBlocking {
        val jobId = UUID.randomUUID().toString()
        val repository = IqTestJobRepository(app.databaseFactory.database)
        val channel = createChannel("persisted-job", "iq-json")
        repository.create(
            NewIqTestJob(
                jobId = jobId,
                requestJson = """{"channelId":"${channel.id}","model":"gpt-4o-mini","benchmark":"candy"}""",
                prompt = "persisted prompt",
                questionId = null,
                questionTitle = null,
                answerType = null,
                expectedAnswerValue = null,
                judgeId = null,
            ),
        )

        val registry = IqTestJobRegistry(repository)
        val running = registry.running()
        assertEquals(1, running.count { it.jobId == jobId })
        val recovered = running.single { it.jobId == jobId }
        assertEquals(IqTestJobStatus.RUNNING, recovered.status)
        assertEquals("persisted prompt", recovered.prompt)
        app.resumeIqTestJobs(listOf(recovered))
        var snapshot = assertNotNull(registry.find(jobId))
        for (attempt in 0 until 50) {
            if (snapshot.status != IqTestJobStatus.RUNNING) break
            kotlinx.coroutines.delay(20)
            snapshot = assertNotNull(registry.find(jobId))
        }
        assertEquals(IqTestJobStatus.COMPLETED, snapshot.status)
        assertEquals("persisted prompt", snapshot.prompt)
        assertNotNull(snapshot.runId)
        assertTrue(registry.running().none { it.jobId == jobId })
    }
}
