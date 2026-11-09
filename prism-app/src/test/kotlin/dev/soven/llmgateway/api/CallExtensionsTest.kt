package dev.soven.llmgateway.api

import dev.soven.llmgateway.shared.error.GatewayError

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 [CallExtensions] 统一请求参数提取、类型转换与错误响应逻辑。
 *
 * @author 文杰
 * @since 2026-10-24
 */
class CallExtensionsTest {

    @Test
    fun `requiredParam returns value when present and throws 400 when missing`() = testApplication {
        install(StatusPages) {
            exception<GatewayError> { call, cause ->
                call.respondText("${cause.code}: ${cause.message}", status = cause.status)
            }
        }
        routing {
            get("/test/{id}") {
                val id = call.requiredParam("id", "缺少渠道 id")
                call.respondText(id)
            }
        }

        val success = client.get("/test/channel-abc")
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals("channel-abc", success.bodyAsText())

        val missing = client.get("/test/%20%20") // 空白字符串
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertTrue(missing.bodyAsText().contains("missing_id"))
        assertTrue(missing.bodyAsText().contains("缺少渠道 id"))
    }

    @Test
    fun `requiredQuery returns value when present and throws 400 when missing`() = testApplication {
        install(StatusPages) {
            exception<GatewayError> { call, cause ->
                call.respondText("${cause.code}: ${cause.message}", status = cause.status)
            }
        }
        routing {
            get("/query") {
                val model = call.requiredQuery("model", "缺少模型名称")
                call.respondText(model)
            }
        }

        val success = client.get("/query?model=deepseek-flash")
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals("deepseek-flash", success.bodyAsText())

        val missing = client.get("/query")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertTrue(missing.bodyAsText().contains("missing_model"))
        assertTrue(missing.bodyAsText().contains("缺少模型名称"))
    }

    @Test
    fun `queryInt parses integer with fallback and bounds`() = testApplication {
        routing {
            get("/int") {
                val limit = call.queryInt("limit", default = 100, min = 1, max = 1000)
                call.respondText(limit.toString())
            }
        }

        assertEquals("50", client.get("/int?limit=50").bodyAsText())
        assertEquals("100", client.get("/int").bodyAsText())
        assertEquals("100", client.get("/int?limit=invalid").bodyAsText())
        assertEquals("1000", client.get("/int?limit=5000").bodyAsText())
        assertEquals("1", client.get("/int?limit=-5").bodyAsText())
    }

    @Test
    fun `queryLongOrNull parses long or returns null`() = testApplication {
        routing {
            get("/long") {
                val id = call.queryLongOrNull("beforeId")
                call.respondText(id?.toString() ?: "null")
            }
        }

        assertEquals("1234567890123", client.get("/long?beforeId=1234567890123").bodyAsText())
        assertEquals("null", client.get("/long?beforeId=abc").bodyAsText())
        assertEquals("null", client.get("/long").bodyAsText())
    }
}
