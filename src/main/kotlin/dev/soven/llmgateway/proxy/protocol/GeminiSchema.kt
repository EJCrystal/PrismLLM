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
 * 这里把 schema 收敛到 Gemini 能表达的子集：删掉多余关键字、展开本地 $ref、摊平 allOf、
 * const 转 enum、oneOf 转 anyOf、补齐 type。
 *
 * 转换是有损的：Schema 无法表达的约束（not、外部引用、递归引用等）会被丢弃或明确报错，只保留结构和值域。
 */
private val geminiSchemaKeywords = setOf(
    "format", "title", "description", "nullable", "default",
    "minItems", "maxItems", "required", "minProperties", "maxProperties",
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
internal fun geminiParameters(schema: JsonElement?, path: String = "parameters"): JsonObject =
    schema?.let {
        convertGeminiSchema(
            schema = it,
            defs = schemaDefinitions(it),
            isRoot = true,
            depth = 0,
            path = path,
            refs = emptySet(),
        )
    } ?: buildJsonObject { put("type", "object") }

/** 收集根 schema 的本地 $defs / definitions，供 $ref 展开。 */
private fun schemaDefinitions(schema: JsonElement): Map<String, JsonElement> {
    val root = schema as? JsonObject ?: return emptyMap()
    val definitions = LinkedHashMap<String, JsonElement>()
    listOf("\$defs", "definitions").forEach { keyword ->
        (root[keyword] as? JsonObject)?.forEach { (name, definition) ->
            definitions["#/$keyword/$name"] = definition
        }
    }
    return definitions
}

private data class ResolvedSchema(val schema: JsonObject?, val refs: Set<String>)

/**
 * Gemini 的 Schema 没有引用，本地 $ref 只能展开；展开后仍带 $ref 的节点继续解析。
 * 循环引用和外部引用都无法表达，直接报错而不是输出无类型节点。
 */
private fun resolveReference(
    source: JsonObject,
    defs: Map<String, JsonElement>,
    refs: Set<String>,
    path: String,
): ResolvedSchema {
    var current = source
    var seen = refs
    while (true) {
        val ref = current.string("\$ref") ?: return ResolvedSchema(current, seen)
        if (ref in seen) unsupported("$path.\$ref", "工具 schema 的 \$ref 循环引用，Gemini 无法表达递归结构")
        val target = defs[ref] ?: unsupported("$path.\$ref", "无法解析工具 schema 的 \$ref: $ref")
        seen = seen + ref
        current = when (target) {
            // draft-07 忽略 $ref 的兄弟字段，2020-12 生效；这里合并保留，行为更接近后者。
            is JsonObject -> JsonObject(target + (current - "\$ref"))
            is JsonPrimitive -> when {
                target.isString -> unsupported("$path.\$ref", "工具 schema 的 \$ref 目标必须是对象: $ref")
                target.booleanOrNull == true -> JsonObject(current - "\$ref")
                else -> return ResolvedSchema(null, seen)
            }
            else -> unsupported("$path.\$ref", "工具 schema 的 \$ref 目标必须是对象: $ref")
        }
    }
}

/**
 * allOf 是交集，Gemini 没有对应关键字：把分支摊平进节点，属性与 required 取并集，
 * 同名属性递归合并，其余关键字以先出现的为准。分支里的 allOf 继续摊平。
 */
private fun mergeAllOf(
    source: JsonObject,
    defs: Map<String, JsonElement>,
    refs: Set<String>,
    path: String,
    depth: Int,
): ResolvedSchema {
    if (depth > MAX_GEMINI_SCHEMA_DEPTH) return ResolvedSchema(source, refs)
    val branches = source["allOf"] as? JsonArray ?: return ResolvedSchema(source, refs)
    var merged = JsonObject(source - "allOf")
    var mergedRefs = refs
    branches.forEachIndexed { index, branch ->
        val branchPath = "$path.allOf[$index]"
        val resolved = when (branch) {
            is JsonObject -> resolveReference(branch, defs, mergedRefs, branchPath)
            is JsonPrimitive -> when {
                branch.isString -> unsupported(branchPath, "工具 schema 的 allOf 分支必须是对象")
                branch.booleanOrNull == true -> ResolvedSchema(JsonObject(emptyMap()), mergedRefs)
                branch.booleanOrNull == false ->
                    unsupported(branchPath, "工具 schema 的 allOf 含 false 分支，没有可表达的值域")
                else -> unsupported(branchPath, "工具 schema 的 allOf 分支必须是对象")
            }
            else -> unsupported(branchPath, "工具 schema 的 allOf 分支必须是对象")
        }
        val schema = resolved.schema ?: unsupported(branchPath, "工具 schema 的 allOf 分支没有可表达的值域")
        mergedRefs = resolved.refs
        merged = mergeSchema(merged, schema, branchPath)
    }
    return if (merged.containsKey("allOf")) {
        mergeAllOf(merged, defs, mergedRefs, path, depth + 1)
    } else {
        ResolvedSchema(merged, mergedRefs)
    }
}

private fun mergeSchema(base: JsonObject, branch: JsonObject, path: String): JsonObject {
    val merged = LinkedHashMap(base)
    branch.forEach { (key, value) ->
        when (key) {
            "properties" -> {
                val existing = merged["properties"] as? JsonObject
                val incoming = value as? JsonObject ?: return@forEach
                merged["properties"] = if (existing == null) {
                    incoming
                } else {
                    JsonObject(
                        existing + incoming.mapValues { (name, property) ->
                            val left = existing[name] as? JsonObject
                            val right = property as? JsonObject
                            if (left != null && right != null) mergeSchema(left, right, "$path.properties.$name") else property
                        },
                    )
                }
            }
            "required" -> {
                val existing = (merged["required"] as? JsonArray).orEmpty()
                val incoming = (value as? JsonArray).orEmpty()
                merged["required"] = JsonArray((existing + incoming).distinct())
            }
            "allOf" -> {
                val existing = merged["allOf"] as? JsonArray
                val incoming = value as? JsonArray ?: return@forEach
                merged["allOf"] = if (existing == null) incoming else JsonArray(existing + incoming)
            }
            else -> if (!merged.containsKey(key)) merged[key] = value
        }
    }
    return JsonObject(merged)
}

private fun convertGeminiSchema(
    schema: JsonElement,
    defs: Map<String, JsonElement>,
    isRoot: Boolean,
    depth: Int,
    path: String,
    refs: Set<String>,
): JsonObject? {
    if (depth > MAX_GEMINI_SCHEMA_DEPTH) return buildJsonObject { put("type", "string") }
    val raw = when (schema) {
        is JsonObject -> schema
        // true 表示任意值，按空 schema 处理；false 表示没有合法值，由调用方丢弃。
        is JsonPrimitive -> when {
            schema.isString -> return null
            schema.booleanOrNull == true -> JsonObject(emptyMap())
            else -> return null
        }
        else -> return null
    }
    val reference = resolveReference(raw, defs, refs, path)
    val resolved = reference.schema ?: return null
    val allOf = mergeAllOf(resolved, defs, reference.refs, path, depth)
    val source = allOf.schema ?: return null
    val nodeRefs = allOf.refs
    val out = LinkedHashMap<String, JsonElement>()
    val hasItems = source.containsKey("items")
    source.forEach { (key, value) ->
        when {
            key.startsWith("x-") || key == "\$ref" || key == "const" || key == "type" -> Unit
            key == "properties" && value is JsonObject -> {
                val properties = LinkedHashMap<String, JsonElement>()
                value.forEach { (name, property) ->
                    convertGeminiSchema(
                        schema = property,
                        defs = defs,
                        isRoot = false,
                        depth = depth + 1,
                        path = "$path.properties.$name",
                        refs = nodeRefs,
                    )?.let { properties[name] = it }
                }
                if (properties.isNotEmpty()) out["properties"] = JsonObject(properties)
            }
            key == "items" || (key == "prefixItems" && !hasItems) -> {
                val items = if (value is JsonArray) {
                    tupleItems(value, defs, depth, path, nodeRefs)
                } else {
                    convertGeminiSchema(value, defs, isRoot = false, depth = depth + 1, path = "$path.items", refs = nodeRefs)
                }
                if (items != null) out["items"] = items
            }
            key == "enum" -> geminiStringEnum(value)?.let { out["enum"] = it }
            key == "anyOf" || key == "oneOf" -> {
                val branches = (value as? JsonArray)
                    ?.mapIndexedNotNull { index, branch ->
                        convertGeminiSchema(
                            schema = branch,
                            defs = defs,
                            isRoot = false,
                            depth = depth + 1,
                            path = "$path.$key[$index]",
                            refs = nodeRefs,
                        )
                    }
                    .orEmpty()
                if (branches.isEmpty()) unsupported("$path.$key", "工具 schema 的 $key 分支都无法转换为 Gemini 类型")
                out["anyOf"] = JsonArray(branches)
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
        (source["const"] as? JsonPrimitive)?.takeIf { it.isString }?.let { out["enum"] = JsonArray(listOf(it)) }
    }
    if (out["type"] == null && out["anyOf"] == null) {
        val inferred = geminiSchemaType(source, isRoot)
            ?: unsupported(path, "工具 schema 的该节点无法推断出 Gemini 支持的类型")
        out["type"] = JsonPrimitive(inferred)
    }
    return JsonObject(out)
}

/** JSON Schema 的 tuple 形式（items 是数组，或 2020-12 的 prefixItems）在 Schema 里没有对应表示，按元素类型的 anyOf 近似。 */
private fun tupleItems(
    tuple: JsonArray,
    defs: Map<String, JsonElement>,
    depth: Int,
    path: String,
    refs: Set<String>,
): JsonObject? {
    val branches = tuple.mapIndexedNotNull { index, element ->
        convertGeminiSchema(element, defs, isRoot = false, depth = depth + 1, path = "$path[$index]", refs = refs)
    }
    return when (branches.size) {
        0 -> null
        1 -> branches.single()
        else -> buildJsonObject { put("anyOf", JsonArray(branches)) }
    }
}

/** Gemini 的 Schema.enum 是 repeated string；非字符串枚举值无法表达，只能放弃值域约束、保留推断出的类型。 */
private fun geminiStringEnum(value: JsonElement): JsonArray? {
    val values = value as? JsonArray ?: return null
    if (values.isEmpty()) return null
    val strings = values.filterIsInstance<JsonPrimitive>().filter { it.isString }
    return if (strings.size == values.size) JsonArray(strings) else null
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
    // 分支或引用推断不出唯一类型时保持不写，由调用方按无法表达处理。
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
    // booleanOrNull 按内容解析，字符串 "true" 也会命中，所以先判字符串。
    if (primitive.isString) return "string"
    primitive.booleanOrNull?.let { return "boolean" }
    val content = primitive.contentOrNull ?: return null
    return if (content.any { it == '.' || it == 'e' || it == 'E' }) "number" else "integer"
}
