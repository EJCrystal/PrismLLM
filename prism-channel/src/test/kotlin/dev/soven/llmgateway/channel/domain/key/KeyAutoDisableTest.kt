package dev.soven.llmgateway.channel.domain.key


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KeyAutoDisableTest {
    @Test
    fun `three consecutive auth failures disable and other outcomes break the streak`() {
        val policy = KeyAutoDisablePolicy(failureThreshold = 100)
        var state = KeyFailureState()
        repeat(2) {
            val result = policy.record(state, KeyAttemptOutcome.AUTH_FAILURE, it.toLong())
            assertNull(result.reason)
            state = result.state
        }
        assertEquals(KeyAutoDisablePolicy.REASON_AUTH_STREAK, policy.record(state, KeyAttemptOutcome.AUTH_FAILURE, 2).reason)
        for (outcome in listOf(KeyAttemptOutcome.SUCCESS, KeyAttemptOutcome.FAILURE, KeyAttemptOutcome.OTHER_RESPONSE)) {
            val reset = policy.record(state, outcome, 3)
            assertEquals(0, reset.state.authFailureStreak)
            assertNull(policy.record(reset.state, KeyAttemptOutcome.AUTH_FAILURE, 4).reason)
        }
    }

    @Test
    fun `window keeps failures across successes and excludes exact expired boundary`() {
        val policy = KeyAutoDisablePolicy(authFailureThreshold = 100)
        var state = KeyFailureState()
        repeat(4) { state = policy.record(state, KeyAttemptOutcome.FAILURE, 0).state }
        val success = policy.record(state, KeyAttemptOutcome.SUCCESS, 1000)
        assertEquals(4, success.state.recentFailures.size)
        assertEquals(KeyAutoDisablePolicy.REASON_FAILURE_WINDOW, policy.record(success.state, KeyAttemptOutcome.FAILURE, 299_999).reason)
        val expired = policy.record(state, KeyAttemptOutcome.FAILURE, 300_000)
        assertNull(expired.reason)
        assertEquals(listOf(300_000L), expired.state.recentFailures)
    }

    @Test
    fun `non counted responses preserve window but reset auth streak`() {
        val policy = KeyAutoDisablePolicy()
        val failed = policy.record(KeyFailureState(), KeyAttemptOutcome.AUTH_FAILURE, 1)
        val ignored = policy.record(failed.state, KeyAttemptOutcome.OTHER_RESPONSE, 2)
        assertEquals(0, ignored.state.authFailureStreak)
        assertEquals(listOf(1L), ignored.state.recentFailures)
    }

    @Test
    fun `disabled policy does not count and configurable limits apply`() {
        assertEquals(KeyFailureState(), KeyAutoDisablePolicy(enabled = false).record(
            KeyFailureState(2, listOf(0, 1)), KeyAttemptOutcome.AUTH_FAILURE, 2,
        ).state)
        assertEquals(KeyAutoDisablePolicy.REASON_FAILURE_WINDOW, KeyAutoDisablePolicy(failureThreshold = 1).record(
            KeyFailureState(), KeyAttemptOutcome.FAILURE, 0,
        ).reason)
        assertFailsWith<IllegalArgumentException> { KeyAutoDisablePolicy(authFailureThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { KeyAutoDisablePolicy(failureThreshold = 1001) }
        assertFailsWith<IllegalArgumentException> { KeyAutoDisablePolicy(failureWindowMinutes = 1441) }
    }
}
