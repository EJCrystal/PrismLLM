package dev.soven.prismllm.proxy.fingerprint

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class FingerprintDetectorTest {
    private val detector = FingerprintDetector.loadDefault()

    @Test
    fun `parses the longest run of in-range integers`() {
        assertEquals(
            listOf(1, 2, 300, 355, 4, 5),
            parseFingerprintNumbers("说明 abc 1, 2 300 355 356 0 4 5").toList(),
        )
        assertEquals(
            listOf(101, 102, 103),
            parseFingerprintNumbers("first 1, 2 abc 101 102 103").toList(),
        )
        assertEquals(2, countOutOfRangeIntegers("1 356 -2 355"))
        assertEquals(listOf(1, 2, 3), parseFingerprintNumbers("-1 -2 -3 1 2 3 -4").toList())
        assertEquals(emptyList<Int>(), parseFingerprintNumbers("-1 -2 -3").toList())
    }

    @Test
    fun `reference samples rank their own model first with calibrated confidence`() {
        val samples = loadSamples()
        val outputs = samples.map { FingerprintOutput(expectedCount = it.expectedCount, text = it.text) }
        val analysis = detector.analyze(outputs)

        assertEquals("scored", analysis.decision)
        assertEquals(3, analysis.usedOutputs)
        assertEquals("claude-sonnet-4.6", analysis.prediction)
        assertEquals("Claude", analysis.familyPredictionName)
        assertEquals("reference_calibrated", analysis.probabilityStatus)
        assertEquals(0.9999994997519885, analysis.probability!!, 1e-9)
        assertEquals(4.882661840816606, analysis.rankingScore!!, 1e-5)
        assertEquals(-0.6536161598919696, analysis.sequence!!.autocorrelation, 1e-9)
        assertEquals(0.0, analysis.sequence!!.smallStepShare, 1e-12)

        val top = analysis.results.take(6)
        assertEquals(
            listOf(
                "claude-sonnet-4.6",
                "mimo-v2.5-pro",
                "mimo-v2.5",
                "qwen3.8-max-0902",
                "openpangu-2.0-pro",
                "claude-opus-4.6",
            ),
            top.map { it.model },
        )
        assertEquals(1.7011031175658755, top[1].score, 1e-5)
        assertEquals(2.5446150301103744e-07, top[1].probability!!, 1e-12)
        assertEquals(68, analysis.results.size)
        assertEquals(3, analysis.diagnostics.size)
        assertTrue(analysis.diagnostics.all { it.accepted })
    }

    @Test
    fun `nearest mean distance uses squared reference norms`() {
        val distance = detector.nearestMeanDistance(
            doubleArrayOf(1.0, 0.0),
            listOf(doubleArrayOf(3.0, 4.0)),
            doubleArrayOf(25.0),
        )
        assertEquals(20.0, distance, 1e-12)
    }

    @Test
    fun `rejects regular sequences that do not look like model output`() {
        val outputs = listOf(11, 22, 33).map { seed ->
            val random = kotlin.random.Random(seed)
            FingerprintOutput(300, List(300) { random.nextInt(1, 356) }.joinToString(", "))
        }
        val analysis = detector.analyze(outputs)

        assertEquals("not_model", analysis.decision)
        assertTrue(analysis.results.isEmpty())
    }

    @Test
    fun `needs three complete answers unless partial ranking is enabled`() {
        val samples = loadSamples()
        val partial = detector.analyze(
            listOf(FingerprintOutput(samples[0].expectedCount, samples[0].text)),
            allowPartial = false,
        )
        assertEquals("unscorable", partial.decision)
        assertEquals(1, partial.usedOutputs)

        val ranked = detector.analyze(
            listOf(FingerprintOutput(samples[0].expectedCount, samples[0].text)),
        )
        assertEquals("partial", ranked.decision)
        assertNotNull(ranked.prediction)
        assertEquals(null, ranked.probability)
    }

    @Test
    fun `generates three distinct challenge lengths from the reference range`() {
        val challenges = FingerprintChallengeGenerator.generate()
        assertEquals(3, challenges.size)
        assertEquals(3, challenges.map { it.expectedCount }.toSet().size)
        assertTrue(challenges.all { it.expectedCount in 292..332 })
        assertTrue(challenges.all { it.prompt.contains(it.expectedCount.toString()) })
        assertTrue(challenges.all { it.prompt.contains("355") })
    }

    private fun loadSamples(): List<SampleFixture> {
        val text = FingerprintDetectorTest::class.java.getResourceAsStream("/fingerprint/real_samples.json")
            ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            ?: error("missing fixture")
        return Json { ignoreUnknownKeys = true }.decodeFromString(text)
    }

    @Serializable
    private data class SampleFixture(
        @SerialName("expected_count") val expectedCount: Int,
        val text: String,
    )
}