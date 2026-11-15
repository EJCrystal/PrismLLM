package dev.soven.prismllm.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.logstash.logback.encoder.LogstashEncoder
import net.logstash.logback.marker.Markers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Tag

/**
 * 采样依赖 LogstashEncoder 把 LogstashMarker 里的字段平铺进 stdout JSON。
 * 如果 marker 没被输出，日志就只剩一句 message，排障时拿不到 max_output_tokens。
 */
@Tag("unit")
class RequestSamplingLogEncodingTest {

    @Test
    fun `logstash encoder emits sampled control parameters as json fields`() {
        val context = LoggerContext()
        context.setMDCAdapter(LogbackMDCAdapter())
        val encoder = LogstashEncoder()
        encoder.context = context
        encoder.start()
        try {
            val event = LoggingEvent()
            event.loggerName = "dev.soven.prismllm.api.RequestSampling"
            event.level = Level.INFO
            event.message = "request body sampling"
            event.timeStamp = System.currentTimeMillis()
            event.threadName = Thread.currentThread().name
            event.setLoggerContext(context)
            event.addMarker(
                Markers.appendEntries(
                    linkedMapOf<String, Any?>(
                        "request_id" to "3d6cea5e-6310-4bbe-8f3c-31664d7ae99b",
                        "max_output_tokens" to 160L,
                        "message_count" to 2,
                        "failed" to true,
                        "error_code" to "responses_incomplete",
                        "trace_id" to null,
                        "channel_name" to null,
                    ),
                ),
            )

            val json = Json.parseToJsonElement(String(encoder.encode(event), Charsets.UTF_8)).jsonObject

            assertEquals("3d6cea5e-6310-4bbe-8f3c-31664d7ae99b", json["request_id"]?.jsonPrimitive?.content)
            assertEquals(160L, json["max_output_tokens"]?.jsonPrimitive?.longOrNull)
            assertEquals(2L, json["message_count"]?.jsonPrimitive?.longOrNull)
            assertEquals(true, json["failed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            assertEquals("responses_incomplete", json["error_code"]?.jsonPrimitive?.content)
            assertEquals("request body sampling", json["message"]?.jsonPrimitive?.content)
            // 只输出白名单控制参数，任何正文内容都不应出现。
            assertFalse(json.toString().contains("SECRET_PROMPT"))
        } finally {
            encoder.stop()
        }
    }
}
