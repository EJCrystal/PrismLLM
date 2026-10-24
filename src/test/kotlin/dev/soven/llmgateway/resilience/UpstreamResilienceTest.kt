package dev.soven.llmgateway.resilience

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpstreamResilienceTest {
    @Test
    fun `retry delay grows exponentially and respects Retry-After`() {
        val policy = UpstreamResiliencePolicy(
            backoffBaseMs = 100,
            backoffMaxMs = 300,
            jitterMs = 0,
        )

        assertEquals(100, policy.retryDelayMs(0))
        assertEquals(200, policy.retryDelayMs(1))
        assertEquals(300, policy.retryDelayMs(2))
        assertEquals(300, policy.retryDelayMs(10))
        assertEquals(1_500, policy.retryDelayMs(0, retryAfterMs = 1_500))
    }

    @Test
    fun `circuit opens after threshold and half-open success closes it`() {
        var now = 1_000L
        val breaker = UpstreamCircuitBreaker(
            policy = UpstreamResiliencePolicy(
                circuitFailureThreshold = 2,
                circuitOpenMs = 1_000,
            ),
            nowMs = { now },
        )
        val first = assertNotNull(breaker.acquire("primary"))
        assertEquals(false, breaker.recordFailure(first).openedNow)
        val second = assertNotNull(breaker.acquire("primary"))
        assertEquals(true, breaker.recordFailure(second).openedNow)
        assertEquals(CircuitStatus.OPEN, breaker.status("primary"))
        assertNull(breaker.acquire("primary"))

        now = 2_000L
        val probe = assertNotNull(breaker.acquire("primary"))
        assertEquals(true, probe.halfOpen)
        assertNull(breaker.acquire("primary"))
        breaker.recordSuccess(probe)

        assertEquals(CircuitStatus.CLOSED, breaker.status("primary"))
        val recovered = assertNotNull(breaker.acquire("primary"))
        breaker.recordSuccess(recovered)
    }

    @Test
    fun `half-open failure reopens circuit and cancellation extends cooldown`() {
        var now = 0L
        val breaker = UpstreamCircuitBreaker(
            policy = UpstreamResiliencePolicy(
                circuitFailureThreshold = 1,
                circuitOpenMs = 1_000,
            ),
            nowMs = { now },
        )
        val failed = assertNotNull(breaker.acquire("primary"))
        breaker.recordFailure(failed)
        now = 1_000L
        val probe = assertNotNull(breaker.acquire("primary"))
        assertEquals(true, breaker.recordFailure(probe).openedNow)
        assertNull(breaker.acquire("primary"))

        now = 2_000L
        val cancelled = assertNotNull(breaker.acquire("primary"))
        breaker.release(cancelled)
        assertNull(breaker.acquire("primary"))
        now = 3_000L
        assertNotNull(breaker.acquire("primary"))
    }
}
