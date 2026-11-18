package dev.soven.prismllm.proxy

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Code Assist 客户端身份头的回归测试：替换 UA，且不把 Node 的 Api-Client 带给上游。 */
@Tag("unit")
class UpstreamAuthTest {
    @Test
    fun `code assist identity replaces the UA and drops node api client`() {
        val builder = HttpRequestBuilder()
        builder.headers.append(HttpHeaders.UserAgent, "google-genai-sdk/1.0")
        builder.headers.append("X-Goog-Api-Client", "gl-node/22.21.1")

        builder.applyCodeAssistIdentity(
            "antigravity/hub/2.32.1 (aidev_client; os_type=linux; arch=amd64; cl=1021296516)",
        )

        assertEquals(
            listOf("antigravity/hub/2.32.1 (aidev_client; os_type=linux; arch=amd64; cl=1021296516)"),
            builder.headers.getAll(HttpHeaders.UserAgent),
        )
        assertNull(builder.headers["X-Goog-Api-Client"])
    }
}
