package dev.soven.llmgateway.proxy.protocol

import dev.soven.llmgateway.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 跨协议转 Gemini 时，调用方的 JSON Schema 要收敛到 Gemini Schema 能表达的字段。 */
class GeminiSchemaConversionTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun parameters(request: String): JsonObject {
        val converted = ProtocolConversion.convertRequest(
            GatewayProtocol.MESSAGES,
            GatewayProtocol.GEMINI,
            json(request),
            "upstream",
        )
        return converted["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
            .single().jsonObject["parameters"]!!.jsonObject
    }

    @Test
    fun `unsupported json schema keywords are removed and missing types inferred`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "${'$'}schema":"http://json-schema.org/draft-07/schema#",
            "type":"object",
            "properties":{
                "path":{"description":"file","minLength":1},
                "flags":{"items":{"type":"string"}},
                "mode":{"const":"fast"},
                "extra":{"additionalProperties":false}
            },
            "required":["path"],
            "additionalProperties":false,
            "x-mcp-header":"X-Foo"
        }}]}""")
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertFalse(schema.containsKey("${'$'}schema"))
        assertFalse(schema.containsKey("additionalProperties"))
        assertFalse(schema.containsKey("x-mcp-header"))
        val properties = schema["properties"]!!.jsonObject
        assertEquals("string", properties["path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", properties["flags"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("fast", properties["mode"]!!.jsonObject["enum"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("object", properties["extra"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nullable types and oneOf are expressed the gemini way`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{
                "name":{"type":["string","null"]},
                "size":{"oneOf":[{"type":"integer"},{"type":"number"}]},
                "kind":{"type":["string","integer"]}
            }
        }}]}""")
        val properties = schema["properties"]!!.jsonObject
        val name = properties["name"]!!.jsonObject
        assertEquals("string", name["type"]!!.jsonPrimitive.content)
        assertEquals("true", name["nullable"]!!.jsonPrimitive.content)
        assertFalse(name.containsKey("anyOf"))
        val size = properties["size"]!!.jsonObject
        assertEquals(2, size["anyOf"]!!.jsonArray.size)
        assertFalse(size.containsKey("oneOf"))
        assertEquals(2, properties["kind"]!!.jsonObject["anyOf"]!!.jsonArray.size)
    }

    @Test
    fun `boolean schemas are shaped or dropped`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{
                "anyValue":true,
                "never":false,
                "list":{"type":"array","items":true}
            }
        }}]}""")
        val properties = schema["properties"]!!.jsonObject
        assertFalse(properties.containsKey("never"))
        assertEquals("string", properties["anyValue"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", properties["list"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chat and responses tool schemas use the same conversion`() {
        val chat = ProtocolConversion.convertRequest(
            GatewayProtocol.CHAT_COMPLETIONS,
            GatewayProtocol.GEMINI,
            json(
                """{"model":"m","messages":[],"tools":[{"type":"function","function":{"name":"t","parameters":{"type":"object","properties":{"p":{"description":"no type"}},"additionalProperties":false}}}]}""",
            ),
            "upstream",
        )
        val chatParameters = chat["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
            .single().jsonObject["parameters"]!!.jsonObject
        assertFalse(chatParameters.containsKey("additionalProperties"))
        assertEquals(
            "string",
            chatParameters["properties"]!!.jsonObject["p"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )

        val responses = ProtocolConversion.convertRequest(
            GatewayProtocol.RESPONSES,
            GatewayProtocol.GEMINI,
            json(
                """{"model":"m","input":"hi","tools":[{"type":"function","name":"t","parameters":{"type":"object","properties":{"p":{"enum":["a","b"]}},"additionalProperties":false}}]}""",
            ),
            "upstream",
        )
        val responsesParameters = responses["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
            .single().jsonObject["parameters"]!!.jsonObject
        assertFalse(responsesParameters.containsKey("additionalProperties"))
        assertEquals(
            "string",
            responsesParameters["properties"]!!.jsonObject["p"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }
}
