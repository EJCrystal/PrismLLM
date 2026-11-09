package dev.soven.llmgateway.protocol

import dev.soven.llmgateway.shared.domain.GatewayProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `enum value types distinguish strings from booleans and numbers`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{
                "flag":{"enum":["true","false"]},
                "switches":{"enum":[true,false]},
                "count":{"enum":[1,2]},
                "ratio":{"enum":[1.5,2]}
            }
        }}]}""")
        val properties = schema["properties"]!!.jsonObject
        assertEquals("string", properties["flag"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", properties["switches"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", properties["count"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("number", properties["ratio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
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
    fun `non string enum values keep their type without an invalid enum`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{
                "count":{"enum":[1,2]},
                "ratio":{"enum":[1.5,2]},
                "switches":{"enum":[true,false]},
                "mode":{"const":5},
                "name":{"enum":["a","b"]}
            }
        }}]}""")
        val properties = schema["properties"]!!.jsonObject
        assertEquals("integer", properties["count"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(properties["count"]!!.jsonObject.containsKey("enum"))
        assertEquals("number", properties["ratio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(properties["ratio"]!!.jsonObject.containsKey("enum"))
        assertEquals("boolean", properties["switches"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(properties["switches"]!!.jsonObject.containsKey("enum"))
        assertEquals("integer", properties["mode"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(properties["mode"]!!.jsonObject.containsKey("enum"))
        assertEquals("string", properties["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("a", "b"),
            properties["name"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `tuple items become an anyof element schema`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{
                "pair":{"type":"array","items":[{"type":"string"},{"type":"integer"}]},
                "single":{"type":"array","items":[{"type":"string"}]},
                "legacy":{"type":"array","prefixItems":[{"type":"string"},{"type":"boolean"}]}
            }
        }}]}""")
        val properties = schema["properties"]!!.jsonObject
        val pair = properties["pair"]!!.jsonObject["items"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(listOf("string", "integer"), pair.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(
            "string",
            properties["single"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        val legacy = properties["legacy"]!!.jsonObject["items"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(listOf("string", "boolean"), legacy.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `local refs are expanded and allOf is flattened`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "${'$'}defs":{
                "Color":{"type":"string","enum":["red","blue"]},
                "Point":{"type":"object","properties":{"x":{"type":"integer"}},"required":["x"]}
            },
            "type":"object",
            "properties":{
                "color":{"${'$'}ref":"#/${'$'}defs/Color"},
                "point":{"${'$'}ref":"#/${'$'}defs/Point"},
                "mixed":{"allOf":[
                    {"type":"object","properties":{"a":{"type":"string"}},"required":["a"]},
                    {"properties":{"b":{"type":"integer"}},"required":["b"]}
                ]}
            },
            "required":["color"]
        }}]}""")
        assertFalse(schema.containsKey("${'$'}defs"))
        val properties = schema["properties"]!!.jsonObject
        val color = properties["color"]!!.jsonObject
        assertEquals("string", color["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("red", "blue"), color["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        val point = properties["point"]!!.jsonObject
        assertEquals("object", point["type"]!!.jsonPrimitive.content)
        assertEquals(
            "integer",
            point["properties"]!!.jsonObject["x"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        val mixed = properties["mixed"]!!.jsonObject
        assertEquals("object", mixed["type"]!!.jsonPrimitive.content)
        assertFalse(mixed.containsKey("allOf"))
        assertEquals(listOf("a", "b"), mixed["properties"]!!.jsonObject.keys.sorted())
        assertEquals(
            listOf("a", "b"),
            mixed["required"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
        )
    }

    @Test
    fun `unresolvable references and empty unions are rejected`() {
        val external = assertFailsWith<ProtocolConversionException> {
            parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
                "type":"object","properties":{"x":{"${'$'}ref":"https://example.com/schema.json"}}
            }}]}""")
        }
        assertTrue(external.field.endsWith("properties.x.\$ref"))

        val union = assertFailsWith<ProtocolConversionException> {
            parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
                "type":"object","properties":{"x":{"anyOf":[false,false]}}
            }}]}""")
        }
        assertTrue(union.field.endsWith("properties.x.anyOf"))

        val recursive = assertFailsWith<ProtocolConversionException> {
            parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
                "${'$'}defs":{"Node":{"type":"object","properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}},
                "type":"object","properties":{"root":{"${'$'}ref":"#/${'$'}defs/Node"}}
            }}]}""")
        }
        assertTrue(recursive.field.endsWith("properties.root.properties.next.\$ref"))
    }

    @Test
    fun `allOf branches can reference the same definition`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "${'$'}defs":{"Base":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}},
            "type":"object",
            "properties":{"value":{"allOf":[
                {"${'$'}ref":"#/${'$'}defs/Base"},
                {"${'$'}ref":"#/${'$'}defs/Base"}
            ]}}
        }}]}""")
        val value = schema["properties"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("object", value["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            value["properties"]!!.jsonObject["id"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `closed tuple keeps prefixItems when items is false`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{"pair":{"type":"array","prefixItems":[{"type":"string"},{"type":"integer"}],"items":false}}
        }}]}""")
        val pair = schema["properties"]!!.jsonObject["pair"]!!.jsonObject
        assertEquals("array", pair["type"]!!.jsonPrimitive.content)
        val items = pair["items"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(listOf("string", "integer"), items.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `exponential reference expansion is rejected by the node budget`() {
        val levels = 6
        val definitions = buildString {
            append("\"${'$'}defs\":{")
            (0 until levels).forEach { level ->
                if (level > 0) append(',')
                append("\"L$level\":{\"type\":\"object\",\"properties\":{")
                (0 until 10).forEach { index ->
                    if (index > 0) append(',')
                    append("\"p$index\":{\"${'$'}ref\":\"#/${'$'}defs/L${level + 1}\"}")
                }
                append("}}")
            }
            append(",\"L$levels\":{\"type\":\"string\"}}")
        }
        val failure = assertFailsWith<ProtocolConversionException> {
            parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
                $definitions,
                "type":"object",
                "properties":{"root":{"${'$'}ref":"#/${'$'}defs/L0"}}
            }}]}""")
        }
        assertTrue(failure.message!!.contains("节点数超过上限"))
    }

    @Test
    fun `ref siblings merge with the referenced definition`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "${'$'}defs":{"Base":{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]}},
            "type":"object",
            "properties":{"value":{"${'$'}ref":"#/${'$'}defs/Base","properties":{"y":{"type":"integer"}}}}
        }}]}""")
        val value = schema["properties"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("object", value["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("x", "y"), value["properties"]!!.jsonObject.keys.sorted())
        assertEquals(listOf("x"), value["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `prefix items and item schema are unioned`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "type":"object",
            "properties":{"tuple":{"type":"array","prefixItems":[{"type":"string"}],"items":{"type":"integer"}}}
        }}]}""")
        val items = schema["properties"]!!.jsonObject["tuple"]!!.jsonObject["items"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(listOf("string", "integer"), items.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `escaped definition names resolve`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "${'$'}defs":{"a/b":{"type":"string"}},
            "type":"object",
            "properties":{"value":{"${'$'}ref":"#/${'$'}defs/a~1b"}}
        }}]}""")
        assertEquals(
            "string",
            schema["properties"]!!.jsonObject["value"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `allOf branch refs do not leak into sibling properties`() {
        val schema = parameters("""{"model":"m","max_tokens":8,"messages":[],"tools":[{"name":"t","input_schema":{
            "${'$'}defs":{"Base":{"type":"object","properties":{"id":{"type":"string"}}}},
            "type":"object",
            "properties":{"value":{"allOf":[
                {"${'$'}ref":"#/${'$'}defs/Base"},
                {"properties":{"other":{"${'$'}ref":"#/${'$'}defs/Base"}}}
            ]}}
        }}]}""")
        val value = schema["properties"]!!.jsonObject["value"]!!.jsonObject
        assertEquals(listOf("id", "other"), value["properties"]!!.jsonObject.keys.sorted())
        assertEquals(
            "object",
            value["properties"]!!.jsonObject["other"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
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
