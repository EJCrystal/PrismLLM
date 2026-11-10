package dev.soven.prismllm.plugin.iq

import dev.soven.prismllm.spi.iq.IqJudge
import dev.soven.prismllm.spi.iq.IqJudgeRequest
import dev.soven.prismllm.spi.iq.IqJudgeResult
import dev.soven.prismllm.spi.iq.IqJudgeVerdict
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class IqJudgeServiceTest {
    @Test
    fun `returns judge result`() = runBlocking {
        val registry = IqJudgeRegistry(builtIns = listOf(FakeJudge { IqJudgeResult(IqJudgeVerdict.REVIEW, "待人工") }))
        IqJudgeService(registry, timeoutMs = 2_000).use { service ->
            val result = service.judge("fake", request())
            assertEquals(IqJudgeVerdict.REVIEW, result.verdict)
            assertEquals("待人工", result.label)
        }
        registry.close()
    }

    @Test
    fun `turns timeout into failed result`() = runBlocking {
        val registry = IqJudgeRegistry(builtIns = listOf(FakeJudge {
            Thread.sleep(500)
            IqJudgeResult(IqJudgeVerdict.PASSED, "late")
        }))
        IqJudgeService(registry, timeoutMs = 50).use { service ->
            val result = service.judge("fake", request())
            assertEquals(IqJudgeVerdict.FAILED, result.verdict)
            assertEquals("无法判定", result.label)
        }
        registry.close()
    }

    @Test
    fun `turns plugin exceptions and invalid scores into failed results`() = runBlocking {
        val throwing = IqJudgeRegistry(builtIns = listOf(FakeJudge { error("boom") }))
        IqJudgeService(throwing, timeoutMs = 2_000).use { service ->
            assertEquals(IqJudgeVerdict.FAILED, service.judge("fake", request()).verdict)
        }
        throwing.close()

        val invalidScore = IqJudgeRegistry(builtIns = listOf(FakeJudge {
            IqJudgeResult(IqJudgeVerdict.PASSED, "bad", score = 101.0)
        }))
        IqJudgeService(invalidScore, timeoutMs = 2_000).use { service ->
            assertEquals(IqJudgeVerdict.FAILED, service.judge("fake", request()).verdict)
        }
        invalidScore.close()
    }

    private fun request() = IqJudgeRequest(
        benchmark = "pelican",
        html = "<html><svg/></html>",
        model = "test",
        structure = dev.soven.prismllm.proxy.analyzePelicanStructure("<html><svg/></html>"),
    )

    private class FakeJudge(
        private val block: () -> IqJudgeResult,
    ) : IqJudge {
        override val id = "fake"
        override val displayName = "Fake"
        override fun judge(request: IqJudgeRequest) = block()
    }
}
