package dev.soven.llmgateway.repo

import dev.soven.llmgateway.requestlog.domain.UpstreamAttemptRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpstreamAttemptCodecTest {
    @Test
    fun `round trips upstream reported model`() {
        val attempt = UpstreamAttemptRecord(
            index = 1,
            channelId = "channel",
            upstreamModel = "requested-model",
            upstreamReportedModel = "reported-model",
            elapsedMs = 12,
        )

        assertEquals(listOf(attempt), UpstreamAttemptCodec.decode(UpstreamAttemptCodec.encode(listOf(attempt))))
    }

    @Test
    fun `round trips skipped candidate`() {
        val attempt = UpstreamAttemptRecord(
            index = 0,
            channelId = "channel",
            upstreamModel = "requested-model",
            elapsedMs = 0,
            skipped = true,
            skipReason = "cooldown",
        )

        assertEquals(listOf(attempt), UpstreamAttemptCodec.decode(UpstreamAttemptCodec.encode(listOf(attempt))))
    }

    @Test
    fun `decodes historical attempts without reported model`() {
        val attempts = UpstreamAttemptCodec.decode("[{\"index\":1,\"upstreamModel\":\"requested-model\",\"elapsedMs\":12}]")

        assertEquals(1, attempts.size)
        assertEquals("requested-model", attempts.single().upstreamModel)
        assertNull(attempts.single().upstreamReportedModel)
        assertEquals(false, attempts.single().skipped)
        assertNull(attempts.single().skipReason)
    }
}
