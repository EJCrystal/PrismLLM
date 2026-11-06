package dev.soven.llmgateway.proxy.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Gemini 的 functionDeclarations.parameters 是 Schema 对象而不是完整的 JSON Schema。
 * 调用方（Messages / Chat / Responses）的 schema 直接透传会踩两类 400：
 * 一是 Schema 里不存在的字段（additionalProperties、$schema、$defs、const、oneOf…），
 * 二是 Vertex 对缺少 type 的节点直接拒绝（"schema didn't specify the schema type field"）。
 * 这里把 schema 收敛到 Gemini 能表达的子集：删掉多余关键字、const 转 enum、oneOf 转 anyOf、补齐 type。
 *
 * 转换是有损的：Schema 无法表达的约束（allOf、not、$ref 的目标等）会被丢弃，只保留结构和值域。
 */
private val geminiSchemaKeywords = setOf(
    "format", "title", "description", "nullable", "default",
    "minItems", "maxItems", "enum", "required", "minProperties", "maxProperties",
    "minimum", "maximum", "minLength", "maxLength", "pattern", "example", "propertyOrdering",
)

private val geminiObjectKeywords = setOf(
    "properties", "required", "additionalProperties", "patternProperties", "minProperties", "maxProperties",
)

private val geminiArrayKeywords = setOf("items", "prefixItems", "minItems", "maxItems", "uniqueItems", "contains")

private val geminiStringKeywords = setOf("minLength", "maxLength", "pattern", "format")

private val geminiNumberKeywords = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")

/** 防止异常 schema 递归过深；正常工具声明远达不到这个层数。 */
private const val MAX_GEMINI_SCHEMA_DEPTH = 16

/** 工具声明的 parameters；schema 缺失或无法转换时退回空 object schema。 */
internal fun geminiParameters(schema: JsonElement?): JsonObject =
    schema?.let { convertGeminiSchema(it, isRoot = true, depth = 0) }
        ?: buildJsonObject { put("type", "object") }

private fun convertGeminiSchema(schema: JsonElement, isRoot: Boolean, depth: Int): JsonObject? {
    if (depth > MAX_GEMINI_SCHEMA_DEPTH) return buildJsonObject { put("type", "string") }
    val source = when (schema) {
        is JsonObject -> schema
        // true 表示任意值，按空 schema 处理；false 表示没有合法值，由调用方丢弃。
        is JsonPrimitive -> when (schema.booleanOrNull) {
            true -> JsonObject(emptyMap())
            else -> return null
        }
        else -> return null
    }
    val out = LinkedHashMap<String, JsonElement>()
    source.forEach { (key, value) ->
        when {
            key.startsWith("x-") || key == "\$ref" || key == "const" || key == "type" -> Unit
            key == "properties" && value is JsonObject -> {
                val properties = LinkedHashMap<String, JsonElement>()
                value.forEach { (name, property) ->
                    convertGeminiSchema(property, isRoot = false, depth = depth + 1)?.let { properties[name] = it }
                }
                if (properties.isNotEmpty()) out["properties"] = JsonObject(properties)
            }
            key == "items" -> convertGeminiSchema(value, isRoot = false, depth = depth + 1)?.let { out["items"] = it }
            key == "anyOf" || key == "oneOf" -> {
                val branches = (value as? JsonArray)
                    ?.mapNotNull { convertGeminiSchema(it, isRoot = false, depth = depth + 1) }
                    .orEmpty()
                if (branches.isNotEmpty()) out["anyOf"] = JsonArray(branches)
            }
            key in geminiSchemaKeywords -> if (value !is JsonNull) out[key] = value
            else -> Unit
        }
    }
    when (val type = source["type"]) {
        is JsonPrimitive -> type.contentOrNull?.let { out["type"] = JsonPrimitive(it) }
        is JsonArray -> {
            // JSON Schema 用 ["string","null"] 表达可空，Gemini 用 nullable。
            val types = type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.distinct()
            val rest = types.filter { it != "null" }
            if ("null" in types && out["nullable"] == null) out["nullable"] = JsonPrimitive(true)
            when {
                rest.size == 1 -> out["type"] = JsonPrimitive(rest.single())
                rest.size > 1 && out["anyOf"] == null ->
                    out["anyOf"] = JsonArray(rest.map { buildJsonObject { put("type", it) } })
            }
        }
        else -> Unit
    }
    if (out["enum"] == null) {
        source["const"]?.takeIf { it !is JsonNull }?.let { out["enum"] = JsonArray(listOf(it)) }
    }
    if (out["type"] == null && out["anyOf"] == null) {
        geminiSchemaType(source, isRoot)?.let { out["type"] = JsonPrimitive(it) }
    }
    return JsonObject(out)
}

private fun geminiSchemaType(schema: JsonObject, isRoot: Boolean): String? {
    // 结构关键字优先于值域：带 items 又有字符串 enum 的节点是数组，不是字符串。
    if (schema.keys.any { it in geminiObjectKeywords }) return "object"
    if (schema.keys.any { it in geminiArrayKeywords }) return "array"
    val values = when {
        schema["enum"] is JsonArray -> (schema["enum"] as JsonArray).filter { it !is JsonNull }
        schema["const"] != null && schema["const"] !is JsonNull -> listOf(schema["const"]!!)
        else -> emptyList()
    }
    if (values.isNotEmpty()) return geminiValuesType(values)
    if (schema.keys.any { it in geminiStringKeywords }) return "string"
    if (schema.keys.any { it in geminiNumberKeywords }) return "number"
    if (isRoot) return "object"
    // 分支或引用推断不出唯一类型时保持不写，和 Magpie 对 anyOf/oneOf/allOf/$ref 的处理一致。
    if (schema.keys.any { it == "anyOf" || it == "oneOf" || it == "allOf" || it == "\$ref" }) return null
    schema["default"]?.takeIf { it !is JsonNull }?.let { value ->
        return when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            else -> geminiValueType(value)
        }
    }
    return "string"
}

private fun geminiValuesType(values: List<JsonElement>): String? {
    var type: String? = null
    values.forEach { value ->
        val current = geminiValueType(value) ?: return null
        type = when {
            type == null || type == current -> current
            type == "integer" && current == "number" || type == "number" && current == "integer" -> "number"
            else -> return null
        }
    }
    return type
}

private fun geminiValueType(value: JsonElement): String? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.booleanOrNull?.let { return "boolean" }
    if (primitive.isString) return "string"
    val content = primitive.contentOrNull ?: return null
    return if (content.any { it == '.' || it == 'e' || it == 'E' }) "number" else "integer"
}
