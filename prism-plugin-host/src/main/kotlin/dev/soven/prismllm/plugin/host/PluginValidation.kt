package dev.soven.prismllm.plugin.host

import dev.soven.prismllm.plugin.sdk.DiagnosticTarget
import dev.soven.prismllm.plugin.sdk.PluginAction
import dev.soven.prismllm.plugin.sdk.PluginApi
import dev.soven.prismllm.plugin.sdk.PluginField
import dev.soven.prismllm.plugin.sdk.PluginManifest
import dev.soven.prismllm.plugin.sdk.PluginUiDescriptor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** The deployment catalogue is trusted, but its packages are still data until startup admission. */
internal object PluginValidation {
    const val MAX_METADATA_BYTES = 256 * 1024
    const val MAX_CATALOG_BYTES = 512 * 1024
    const val MAX_PACKAGES = 128
    const val MAX_PROMPT_CHARS = 4_000
    private val pluginId = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    private val version = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
    private val identifier = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
    private val fieldKey = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val kinds = setOf("text", "textarea", "select", "boolean", "number", "model", "credential", "protocol", "scope")
    private val placements = setOf("channel-tests", "oauth-account-tests", "plugin-page")
    private val forbiddenKeys = setOf("url", "endpoint", "baseurl", "headers", "apikey", "accesstoken", "refreshtoken", "secret", "password")

    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
    }

    fun identity(id: String, packageVersion: String) {
        requireData(id.length <= 80 && pluginId.matches(id), "Invalid plugin id")
        requireData(packageVersion.length <= 64 && version.matches(packageVersion), "Invalid plugin version")
    }

    fun digest(value: String): String {
        requireData(Regex("[0-9a-fA-F]{64}").matches(value), "Invalid package SHA-256")
        return value.lowercase()
    }

    fun fileName(value: String) {
        requireData(
            value.length <= 200 && Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.prismplugin").matches(value) && !value.contains(".."),
            "Catalogue package file must be a local .prismplugin basename",
        )
    }

    inline fun <reified T> decode(bytes: ByteArray, description: String): T {
        try {
            val text = bytes.toString(Charsets.UTF_8)
            requireData(text.toByteArray(Charsets.UTF_8).contentEquals(bytes), "$description must be UTF-8")
            checkJsonShape(text)
            return json.decodeFromString<T>(text)
        } catch (error: PluginHostException) {
            throw error
        } catch (_: SerializationException) {
            invalid("Invalid $description JSON or schema")
        } catch (_: IllegalArgumentException) {
            invalid("Invalid $description JSON or schema")
        }
    }

    /** Bound nesting before the JSON parser and reject duplicate keys, including escaped aliases. */
    fun checkJsonShape(text: String) {
        data class Frame(val objectValue: Boolean, var keyExpected: Boolean = objectValue, val keys: MutableSet<String> = HashSet())
        val stack = ArrayList<Frame>()
        var i = 0
        var keys = 0
        while (i < text.length) {
            when (text[i]) {
                '{', '[' -> {
                    requireData(stack.size < 32, "JSON nesting exceeds the supported limit")
                    stack.add(Frame(text[i] == '{'))
                }
                '}', ']' -> {
                    requireData(stack.isNotEmpty() && stack.last().objectValue == (text[i] == '}'), "Invalid JSON structure")
                    stack.removeAt(stack.lastIndex)
                }
                ',' -> if (stack.lastOrNull()?.objectValue == true) stack.last().keyExpected = true
                ':' -> if (stack.lastOrNull()?.objectValue == true) stack.last().keyExpected = false
                '"' -> {
                    val start = i++
                    var escaped = false
                    while (i < text.length) {
                        val c = text[i]
                        if (!escaped && c == '"') break
                        escaped = !escaped && c == '\\'
                        i++
                    }
                    requireData(i < text.length, "Invalid JSON string")
                    val frame = stack.lastOrNull()
                    if (frame?.objectValue == true && frame.keyExpected) {
                        val key = json.decodeFromString<String>(text.substring(start, i + 1))
                        requireData(frame.keys.add(key), "Duplicate JSON property")
                        requireData(++keys <= 4_096, "Too many JSON properties")
                    }
                }
            }
            i++
        }
        requireData(stack.isEmpty(), "Invalid JSON structure")
    }

    fun descriptors(manifest: PluginManifest, ui: PluginUiDescriptor): String? {
        identity(manifest.id, manifest.version)
        requireData(json.encodeToString(PluginManifest.serializer(), manifest).toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES, "Plugin manifest is too large after applying defaults")
        requireData(json.encodeToString(PluginUiDescriptor.serializer(), ui).toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES, "Plugin UI is too large after applying defaults")
        text(manifest.displayName, 200, "Plugin display name", required = true)
        text(manifest.description, 4_000, "Plugin description")
        requireData(manifest.permissions.size <= PluginApi.permissions.size && manifest.permissions.distinct().size == manifest.permissions.size, "Invalid permission list")
        fields(manifest.configuration, configuration = true)
        requireData(ui.entries.size <= 32, "Too many UI entries")
        requireData(ui.entries.map { it.id }.distinct().size == ui.entries.size, "Duplicate UI entry id")
        for (entry in ui.entries) {
            requireData(identifier.matches(entry.id), "Invalid UI entry id")
            text(entry.label, 200, "UI entry label", required = true)
            requireData(entry.placement in placements, "Unsupported UI placement")
            requireData(entry.contexts.isNotEmpty() && entry.contexts.size <= 2 && entry.contexts.distinct().size == entry.contexts.size, "Invalid UI contexts")
            requireData(entry.contexts.all { it in PluginApi.contexts }, "Unsupported diagnostic context")
            requireData(entry.placement != "channel-tests" || entry.contexts == listOf("channel"), "Channel placement requires channel context")
            requireData(entry.placement != "oauth-account-tests" || entry.contexts == listOf("oauth-account"), "Account placement requires account context")
            requireData(identifier.matches(entry.icon), "Invalid declarative icon")
            val action = entry.action
            requireData(identifier.matches(action.id), "Invalid action id")
            text(action.label, 200, "Action label", required = true)
            requireData(action.resource == "model-test-targets", "Unsupported action resource")
            requireData(action.resultKind == "probe-v1", "Unsupported result kind")
            requireData(action.notes.size <= 16, "Too many action notes")
            action.notes.forEach { text(it, 2_000, "Action note") }
            fields(action.fields, configuration = false)
            requireData(action.fields.any { it.key == "model" && it.kind == "model" && it.required }, "Actions require a mandatory host-bound model field")
            requireData(action.fields.any { it.key == "protocol" && it.kind == "protocol" && it.required }, "Actions require a mandatory host-bound protocol field")
            val scope = action.fields.firstOrNull { it.kind == "scope" }
            if (scope != null) {
                val scopeValues = scope.options.map { it.value }.toSet()
                if ("channel" in entry.contexts) {
                    if (action.supportsBatch) {
                        requireData("single" in scopeValues, "Batch channel actions with a scope field must support single-target scope")
                    } else {
                        requireData("all" !in scopeValues, "Non-batch channel actions cannot support all-target scope")
                    }
                }
                if (scope.required) {
                    entry.contexts.forEach { context ->
                        val valid = scopeValues.any { scopeValueAvailable(it, action, context) }
                        requireData(valid, "Required scope field has no valid value for context $context")
                    }
                }
                scope.defaultValue?.let { default ->
                    val defaultScope = (default as? JsonPrimitive)?.takeIf { it.isString }?.content
                    entry.contexts.forEach { context ->
                        requireData(defaultScope != null && scopeValueAvailable(defaultScope, action, context), "Scope default has no valid value for context $context")
                    }
                }
            }
            requireData(!action.supportsBatch || "channel" in entry.contexts, "Batch actions require channel context")
        }
        // Reusing an action across placements is fine; ambiguous schemas for the same context are not.
        for (context in PluginApi.contexts) {
            val actions = ui.entries.filter { context in it.contexts }.map { it.action }.groupBy { it.id }
            requireData(actions.values.all { it.distinct().size == 1 }, "Ambiguous action schema for context")
        }
        val incompatible = when {
            manifest.schemaVersion != 1 -> "Unsupported manifest schema version ${manifest.schemaVersion}"
            ui.schemaVersion != 1 -> "Unsupported UI schema version ${ui.schemaVersion}"
            manifest.apiVersion != PluginApi.VERSION -> "Unsupported plugin API version ${manifest.apiVersion}"
            manifest.permissions.any { it !in PluginApi.permissions } -> "Unsupported plugin permission"
            else -> null
        }
        if (incompatible == null) {
            ui.entries.forEach { entry ->
                entry.contexts.forEach { context ->
                    requireData(permission(context) in manifest.permissions, "Action context requires a declared probe permission")
                }
            }
        }
        return incompatible
    }

    private fun scopeValueAvailable(value: String, action: PluginAction, context: String): Boolean = when (context) {
        "channel" -> value == "single" || (value == "all" && action.supportsBatch)
        "oauth-account" -> value == "single"
        else -> false
    }

    fun permission(context: String): String = when (context) {
        "channel" -> PluginApi.CHANNEL_PROBE
        "oauth-account" -> PluginApi.OAUTH_ACCOUNT_PROBE
        else -> invalid("Unsupported diagnostic context")
    }

    /** Permissions are a complete package approval, not a per-action subset grant. */
    fun grants(manifest: PluginManifest, approved: List<String>): List<String> {
        if (approved.size > PluginApi.permissions.size || approved.distinct().size != approved.size || approved.any { it !in manifest.permissions || it !in PluginApi.permissions }) {
            throw PluginHostException("PLUGIN_INVALID_GRANTS", "Only the package's declared permissions may be approved")
        }
        if (!approved.containsAll(manifest.permissions)) {
            throw PluginHostException("PLUGIN_PERMISSION_REQUIRED", "Explicit approval of all declared plugin permissions is required", 403)
        }
        return manifest.permissions.toList()
    }

    private fun fields(fields: List<PluginField>, configuration: Boolean) {
        requireData(fields.size <= 32, "Too many descriptor fields")
        requireData(fields.map { it.key }.distinct().size == fields.size, "Duplicate field key")
        for (field in fields) {
            requireData(fieldKey.matches(field.key) && field.key.lowercase() !in forbiddenKeys, "Invalid or unsafe field key")
            text(field.label, 200, "Field label", required = true)
            text(field.help, 2_000, "Field help")
            requireData(field.kind in kinds, "Unsupported field kind")
            val resourceKeys = mapOf("model" to "model", "credential" to "keyId", "protocol" to "protocol", "scope" to "scope")
            requireData(resourceKeys[field.kind]?.let { it == field.key } != false, "Resource controls must use their canonical host routing keys")
            requireData(resourceKeys.entries.none { it.value == field.key && it.key != field.kind }, "Canonical resource keys must use their matching control kind")
            requireData(field.maxLength == null || (field.kind in setOf("text", "textarea", "model", "credential", "protocol", "select", "scope") && field.maxLength in 1..MAX_PROMPT_CHARS), "Invalid field length limit")
            val minimum = field.min
            val maximum = field.max
            requireData(minimum == null || (field.kind == "number" && minimum.isFinite()), "Invalid field minimum")
            requireData(maximum == null || (field.kind == "number" && maximum.isFinite()), "Invalid field maximum")
            requireData(minimum == null || maximum == null || minimum <= maximum, "Field minimum exceeds maximum")
            requireData(field.options.size <= 128 && field.options.map { it.value }.distinct().size == field.options.size, "Invalid field choices")
            requireData(field.options.isEmpty() || field.kind in setOf("select", "scope", "model", "credential", "protocol"), "Choices are not supported for this field kind")
            field.options.forEach {
                text(it.value, 256, "Choice value", required = true)
                text(it.label, 200, "Choice label", required = true)
                validateValue(field, JsonPrimitive(it.value), enforceRequired = false)
            }
            requireData(field.kind != "select" || field.options.isNotEmpty(), "Select fields require choices")
            requireData(field.kind != "scope" || (field.options.isNotEmpty() && field.options.all { it.value in setOf("single", "all") }), "Scope choices must be single or all")
            if (field.key in setOf("prompt", "defaultPrompt")) {
                requireData(field.kind in setOf("text", "textarea"), "Prompt fields must be plain text")
            }
            field.defaultValue?.let { validateValue(field, it, enforceRequired = configuration || field.required) }
        }
    }

    fun configuration(fields: List<PluginField>, values: JsonObject, complete: Boolean = true): JsonObject {
        requireData(values.values.all { it is JsonPrimitive }, "Configuration values must be scalars")
        requireData(values.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES, "Configuration is too large")
        requireData(values.keys.all { key -> fields.any { it.key == key } }, "Unknown configuration field")
        val result = LinkedHashMap<String, JsonElement>()
        for (field in fields) {
            val value = values[field.key] ?: field.defaultValue
            if (value == null) {
                requireData(!complete || !field.required, "Missing required field ${field.key}")
            } else {
                validateValue(field, value, enforceRequired = complete)
                result[field.key] = value
            }
        }
        val completed = JsonObject(result)
        requireData(completed.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES, "Configuration is too large after applying defaults")
        return snapshot(completed)
    }

    /** A new schema retains compatible values, never unknown or now-invalid old fields. */
    fun installationConfiguration(fields: List<PluginField>, previous: JsonObject?): JsonObject {
        val retained = LinkedHashMap<String, JsonElement>()
        for (field in fields) {
            val candidate = previous?.get(field.key)
            if (candidate != null) {
                try {
                    validateValue(field, candidate, enforceRequired = field.required)
                    retained[field.key] = candidate
                } catch (_: PluginHostException) {
                    field.defaultValue?.let { retained[field.key] = it }
                }
            } else field.defaultValue?.let { retained[field.key] = it }
        }
        return configuration(fields, JsonObject(retained), complete = false)
    }

    fun input(action: PluginAction, values: JsonObject, target: DiagnosticTarget, configuration: JsonObject, maxPromptChars: Int): JsonObject {
        requireData(values.values.all { it is JsonPrimitive }, "Invocation values must be scalars")
        requireData(values.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES, "Invocation values are too large")
        requireData(values.keys.all { key -> action.fields.any { it.key == key } }, "Unknown invocation field")
        val result = LinkedHashMap<String, JsonElement>()
        for (field in action.fields) {
            var value = values[field.key] ?: when (field.kind) {
                "model" -> JsonPrimitive(target.model)
                "protocol" -> JsonPrimitive(target.protocol)
                "credential" -> target.credentialId?.let(::JsonPrimitive)
                else -> field.defaultValue
            }
            if (field.key == "prompt") {
                if (value == null && field.required) value = configuration["defaultPrompt"]
                if (value is JsonPrimitive && value.isString) {
                    val prompt = value.content.trim()
                    requireData(prompt.length <= maxPromptChars, "Field ${field.key} exceeds its text limit")
                    value = JsonPrimitive(prompt)
                }
            }
            if (value == null) {
                requireData(!field.required, "Missing required field ${field.key}")
                continue
            }
            validateValue(field, value, enforceRequired = true)
            if (value != JsonNull) {
                val string = (value as? JsonPrimitive)?.content
                when (field.kind) {
                    "model" -> requireData(string == target.model, "Model choice does not match the authorized target")
                    "protocol" -> requireData(string == target.protocol, "Protocol choice does not match the authorized target")
                    "credential" -> requireData(string == target.credentialId, "Credential choice does not match the authorized target")
                    "scope" -> requireData(string != "all" || (target.kind == "channel" && action.supportsBatch), "Batch scope is not supported in this context")
                }
            }
            result[field.key] = value
        }
        return snapshot(JsonObject(result))
    }

    private fun validateValue(field: PluginField, value: JsonElement, enforceRequired: Boolean) {
        if (value == JsonNull) {
            requireData(!enforceRequired || !field.required, "Missing required field ${field.key}")
            return
        }
        requireData(value is JsonPrimitive, "Field ${field.key} must be a scalar value")
        val primitive = value as JsonPrimitive
        when (field.kind) {
            "boolean" -> requireData(!primitive.isString && primitive.booleanOrNull != null, "Field ${field.key} must be boolean")
            "number" -> {
                val number = primitive.doubleOrNull
                requireData(!primitive.isString && number != null && number.isFinite(), "Field ${field.key} must be a finite number")
                val minimum = field.min
                val maximum = field.max
                requireData(minimum == null || number!! >= minimum, "Field ${field.key} is below its minimum")
                requireData(maximum == null || number!! <= maximum, "Field ${field.key} exceeds its maximum")
            }
            else -> {
                requireData(primitive.isString, "Field ${field.key} must be text")
                val content = primitive.content
                val max = field.maxLength ?: if (field.kind in setOf("text", "textarea")) MAX_PROMPT_CHARS else 256
                requireData(content.length <= max && !content.contains('\u0000'), "Field ${field.key} exceeds its text limit or contains NUL")
                requireData(!enforceRequired || !field.required || content.isNotBlank(), "Missing required field ${field.key}")
                if (field.options.isNotEmpty()) requireData(field.options.any { it.value == content }, "Invalid choice for field ${field.key}")
            }
        }
    }

    fun target(target: DiagnosticTarget) {
        requireData(target.kind in PluginApi.contexts, "Unsupported diagnostic context")
        text(target.id, 256, "Target identity", required = true)
        text(target.model, 256, "Target model", required = true)
        requireData(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}").matches(target.protocol), "Invalid target protocol")
        text(target.displayName, 512, "Target display name")
        target.credentialId?.let { text(it, 256, "Opaque credential identity", required = true) }
        target.credentialName?.let { text(it, 512, "Credential display name") }
    }

    fun snapshot(values: JsonObject): JsonObject = json.parseToJsonElement(values.toString()).jsonObject

    private fun text(value: String, maximum: Int, label: String, required: Boolean = false) {
        requireData(value.length <= maximum && !value.contains('\u0000') && (!required || value.isNotBlank()), "$label is empty or exceeds its text limit")
    }

    fun requireData(condition: Boolean, message: String) {
        if (!condition) invalid(message)
    }

    fun invalid(message: String): Nothing = throw PluginHostException("PLUGIN_INVALID_PACKAGE", message)
}
