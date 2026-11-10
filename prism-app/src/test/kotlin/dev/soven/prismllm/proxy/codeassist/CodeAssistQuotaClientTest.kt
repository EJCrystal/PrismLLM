package dev.soven.prismllm.proxy.codeassist

import io.ktor.client.HttpClient
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** retrieveUserQuotaSummary 的解析：5h 与 weekly 两个窗口。 */
class CodeAssistQuotaClientTest {
    private val client = CodeAssistQuotaClient(HttpClient())

    @Test
    fun readsBothWindowsAndSkipsDisabledBuckets() {
        val body = """
            {"groups":[{"displayName":"Gemini","buckets":[
              {"window":"5h","remainingFraction":0.75,"resetTime":"2026-11-06T12:00:00Z"},
              {"window":"weekly","remainingFraction":0.5,"resetTime":"2026-11-10T00:00:00Z"},
              {"window":"5h","remainingFraction":0.1,"disabled":true}
            ]}]}
        """.trimIndent()

        val snapshot = client.parse(body, checkedAt = 1_000L)

        assertEquals(1_000L, snapshot!!.checkedAt)
        assertEquals(25.0, snapshot.primary!!.usedPercent)
        assertEquals(50.0, snapshot.secondary!!.usedPercent)
        assertEquals(Instant.parse("2026-11-06T12:00:00Z").toEpochMilli(), snapshot.primary!!.resetsAt)
    }

    @Test
    fun returnsNullWithoutUsableBuckets() {
        assertNull(client.parse("""{"groups":[]}""", 1L))
        assertNull(client.parse("not json", 1L))
    }
}
