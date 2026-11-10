package dev.soven.prismllm.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DurationTextTest {
    @Test
    fun `sub-second durations stay in milliseconds`() {
        assertEquals("0ms", formatDurationText(0))
        assertEquals("999ms", formatDurationText(999))
    }

    @Test
    fun `durations of one second or more switch to seconds`() {
        assertEquals("1s", formatDurationText(1_000))
        assertEquals("30s", formatDurationText(30_000))
        assertEquals("1.5s", formatDurationText(1_500))
        assertEquals("12.3s", formatDurationText(12_340))
    }

    @Test
    fun `rounding never prints a trailing zero`() {
        assertEquals("60s", formatDurationText(59_999))
        assertEquals("2s", formatDurationText(2_000))
    }
}
