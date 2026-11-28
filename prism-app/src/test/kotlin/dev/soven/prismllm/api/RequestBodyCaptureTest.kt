package dev.soven.prismllm.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestBodyCaptureTest {
    @Test
    fun disabledCaptureCountsBytesWithoutKeepingBody() {
        val capture = RequestBodyCapture(false)
        val bytes = "提示词".toByteArray()
        capture.begin()
        capture.append(bytes, bytes.size)
        capture.complete()

        assertNull(capture.body)
        assertEquals(9L, capture.sizeBytes)
        assertTrue(capture.sizeExact)
        assertFalse(capture.truncated)
    }

    @Test
    fun unreadBodyIsNotMistakenForEmptyBody() {
        val capture = RequestBodyCapture(true)
        assertNull(capture.body)
        assertNull(capture.sizeBytes)
        assertFalse(capture.sizeExact)
        assertFalse(capture.truncated)

        capture.begin()
        capture.complete()
        assertEquals("", capture.body)
        assertEquals(0L, capture.sizeBytes)
        assertTrue(capture.sizeExact)
        assertFalse(capture.truncated)
    }

    @Test
    fun rawBodyIsPreservedAcrossChunksIncludingInvalidJsonAndNullBytes() {
        val capture = RequestBodyCapture(true)
        val chunks = listOf("  {\n", "中文", "\u0000broken JSON ")
        capture.begin()
        chunks.forEach { chunk ->
            val bytes = chunk.toByteArray()
            capture.append(bytes, bytes.size)
        }
        capture.complete()

        val expected = chunks.joinToString("")
        assertEquals(expected, capture.body)
        assertEquals(expected.toByteArray().size.toLong(), capture.sizeBytes)
        assertFalse(capture.truncated)
    }

    @Test
    fun bodyAtCaptureLimitIsCompleteAndLargerBodyKeepsOnlyThePrefix() {
        val capture = RequestBodyCapture(true)
        val prefix = ByteArray(64 * 1024) { 'x'.code.toByte() }
        capture.begin()
        capture.append(prefix, prefix.size)
        capture.complete()
        assertEquals(64 * 1024L, capture.sizeBytes)
        assertFalse(capture.truncated)

        val larger = RequestBodyCapture(true)
        larger.begin()
        larger.append(prefix, prefix.size)
        larger.append("tail".toByteArray(), 4)
        larger.complete()
        assertEquals(prefix.toString(Charsets.UTF_8), larger.body)
        assertEquals(64 * 1024L + 4, larger.sizeBytes)
        assertTrue(larger.sizeExact)
        assertTrue(larger.truncated)
    }

    @Test
    fun partialReadKeepsAvailableBytesAndMarksSizeAsALowerBound() {
        val capture = RequestBodyCapture(true)
        capture.begin()
        capture.append("{broken".toByteArray(), 7)

        assertEquals("{broken", capture.body)
        assertEquals(7L, capture.sizeBytes)
        assertFalse(capture.sizeExact)
        assertTrue(capture.truncated)
    }

    @Test
    fun utf8CharacterCrossingChunkBoundaryIsDecodedAfterCapture() {
        val capture = RequestBodyCapture(true)
        val bytes = "中文".toByteArray()
        capture.begin()
        capture.append(bytes.copyOfRange(0, 1), 1)
        capture.append(bytes.copyOfRange(1, bytes.size), bytes.size - 1)
        capture.complete()

        assertEquals("中文", capture.body)
        assertEquals(6L, capture.sizeBytes)
    }

    @Test
    fun utf8CharacterCutByCaptureLimitIsSafeToDisplay() {
        val capture = RequestBodyCapture(true)
        val bytes = ("x".repeat(64 * 1024 - 1) + "中").toByteArray()
        capture.begin()
        capture.append(bytes, bytes.size)
        capture.complete()

        assertTrue(capture.truncated)
        assertTrue(capture.sizeExact)
        assertEquals(64 * 1024L + 2, capture.sizeBytes)
        assertEquals("x".repeat(64 * 1024 - 1) + "\uFFFD", capture.body)
    }
}
