package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.channel.domain.UpstreamHeaderCatalogSnapshot
import dev.soven.llmgateway.channel.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRecord
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class HeaderProfileVersionSource(
    val profileId: String,
    val source: String,
    val fetch: suspend (HttpClient) -> String,
)

@Serializable
private data class HeaderCatalogManifest(
    val schemaVersion: Int = 1,
    val profiles: List<UpstreamHeaderProfile>,
)

private class HeaderCatalogHttpException(val status: Int) : RuntimeException()

/** Refreshes client versions in the background and publishes immutable request snapshots. */
class UpstreamHeaderCatalogService(
    private val httpClient: HttpClient,
    private val repository: UpstreamHeaderCatalogRepository,
    private val refreshIntervalMs: Long,
    private val autoRefreshEnabled: Boolean = true,
    private val manifestUrl: String? = null,
    private val versionSources: List<HeaderProfileVersionSource> = defaultVersionSources(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(refreshIntervalMs >= 60_000) { "请求头目录刷新间隔不能小于 60 秒" }
    }

    private val logger = LoggerFactory.getLogger(UpstreamHeaderCatalogService::class.java)
    private val refreshMutex = Mutex()
    private val state = AtomicReference(
        UpstreamHeaderCatalogSnapshot(
            revision = "bundled",
            checkedAt = 0,
            successAt = null,
            lastError = null,
            refreshing = false,
            autoRefreshEnabled = autoRefreshEnabled,
            refreshIntervalMs = refreshIntervalMs,
            profiles = UpstreamHeaderProfiles.defaults,
            history = emptyMap(),
            pins = emptyMap(),
        ),
    )
    private var refreshJob: Job? = null

    fun profiles(): List<UpstreamHeaderProfile> = state.get().profiles

    fun snapshot(): UpstreamHeaderCatalogSnapshot = state.get()

    suspend fun initialize() {
        publish(repository.load(UpstreamHeaderProfiles.defaults))
    }

    @Synchronized
    fun start(scope: CoroutineScope): Job = refreshJob ?: scope.launch {
        while (isActive) {
            refreshNow()
            delay(refreshIntervalMs)
        }
    }.also { refreshJob = it }

    suspend fun refreshNow(): UpstreamHeaderCatalogSnapshot = refreshMutex.withLock {
        val checkedAt = now()
        state.updateAndGet { it.copy(refreshing = true) }
        try {
            val snapshot = state.get()
            val current = snapshot.profiles.associateBy { it.id }.toMutableMap()
            snapshot.history.forEach { (id, versions) ->
                versions.firstOrNull()?.let { current[id] = it }
            }
            val updatedProfiles = mutableSetOf<String>()
            val errors = mutableListOf<String>()
            var successfulSources = 0

            val sourceResults = supervisorScope {
                versionSources.map { source ->
                    val base = current[source.profileId]
                    async {
                        source to capture {
                            if (base == null) return@capture null
                            applyVersion(base, source.fetch(httpClient), source.source, checkedAt)
                        }
                    }
                }.awaitAll()
            }
            sourceResults.forEach { (source, result) ->
                result.onSuccess { profile ->
                    if (profile == null) return@onSuccess
                    current[source.profileId] = profile
                    updatedProfiles += source.profileId
                    successfulSources += 1
                }.onFailure { cause -> recordSourceFailure(source, cause, errors) }
            }

            val catalogUrl = manifestUrl
            if (!catalogUrl.isNullOrBlank()) {
                capture { fetchManifest(catalogUrl, checkedAt) }
                    .onSuccess { profiles ->
                        profiles.forEach { current[it.id] = it }
                        updatedProfiles += profiles.map { it.id }
                        successfulSources += 1
                    }
                    .onFailure { cause ->
                        val category = safeErrorCategory(cause)
                        logger.warn(
                            "upstream header manifest failed, category={}, type={}",
                            category,
                            cause::class.simpleName ?: "error",
                        )
                        errors += "manifest:" + category
                    }
            }

            val error = errors.joinToString("; ").takeIf { it.isNotBlank() }
            val record = if (successfulSources > 0) {
                repository.saveRefresh(
                    profiles = current.values
                        .filter { it.id in updatedProfiles }
                        .sortedBy { PROFILE_ORDER.indexOf(it.id) },
                    checkedAt = checkedAt,
                    successAt = checkedAt,
                    lastError = error,
                )
            } else {
                repository.saveFailedRefresh(checkedAt, error ?: "没有可用的版本来源")
            }
            publish(record)
            state.get()
        } catch (cause: CancellationException) {
            state.updateAndGet { it.copy(refreshing = false) }
            throw cause
        } catch (cause: Throwable) {
            val message = "refresh_failed"
            logger.warn("upstream header catalog refresh failed: {}", safeErrorCategory(cause))
            persistFailure(now(), message)
            state.get()
        }
    }

    private suspend fun persistFailure(checkedAt: Long, error: String) {
        try {
            publish(repository.saveFailedRefresh(checkedAt, error))
        } catch (cause: CancellationException) {
            state.updateAndGet { it.copy(refreshing = false) }
            throw cause
        } catch (persistenceError: Throwable) {
            logger.warn("failed to persist header catalog refresh error: {}", persistenceError::class.simpleName ?: "error")
            state.updateAndGet { it.copy(refreshing = false, lastError = error.take(1_000)) }
        }
    }

    private fun recordSourceFailure(
        source: HeaderProfileVersionSource,
        cause: Throwable,
        errors: MutableList<String>,
    ) {
        val category = safeErrorCategory(cause)
        logger.warn(
            "upstream header source failed, profile={}, category={}, type={}",
            source.profileId,
            category,
            cause::class.simpleName ?: "error",
        )
        errors += source.profileId + ":" + category
    }

    suspend fun pin(profileId: String, version: String): UpstreamHeaderCatalogSnapshot {
        require(profileId in PROFILE_ORDER) { "未知请求头预设" }
        return refreshMutex.withLock {
            publish(repository.pin(profileId, version))
            state.get()
        }
    }

    suspend fun unpin(profileId: String): UpstreamHeaderCatalogSnapshot {
        require(profileId in PROFILE_ORDER) { "未知请求头预设" }
        return refreshMutex.withLock {
            publish(repository.unpin(profileId))
            state.get()
        }
    }

    private suspend fun fetchManifest(url: String, checkedAt: Long): List<UpstreamHeaderProfile> {
        val text = fetchText(url)
        val manifest = Json { ignoreUnknownKeys = false }.decodeFromString<HeaderCatalogManifest>(text)
        require(manifest.schemaVersion == 1) { "不支持的请求头目录 schemaVersion" }
        require(manifest.profiles.isNotEmpty()) { "请求头目录不能为空" }
        require(manifest.profiles.map { it.id }.distinct().size == manifest.profiles.size) {
            "请求头目录包含重复 profile"
        }
        return manifest.profiles.map { profile ->
            require(upstreamHeaderProfileValidationError(profile) == null) {
                upstreamHeaderProfileValidationError(profile) ?: "profile 无效"
            }
            profile.copy(source = sanitizeCatalogSource(profile.source), fetchedAt = checkedAt)
        }
    }

    private suspend fun fetchText(url: String): String {
        require(url.startsWith("https://")) { "请求头目录 URL 必须使用 HTTPS" }
        return httpClient.prepareGet(url) {
            header(HttpHeaders.UserAgent, "PrismLLM-header-catalog/1.0")
            header(HttpHeaders.Accept, "application/json")
            timeout { requestTimeoutMillis = 15_000 }
        }.execute { response ->
            if (!response.status.isSuccess()) throw HeaderCatalogHttpException(response.status.value)
            readLimitedText(response)
        }
    }

    private fun applyVersion(
        base: UpstreamHeaderProfile,
        version: String,
        source: String,
        fetchedAt: Long,
    ): UpstreamHeaderProfile {
        require(VERSION.matches(version)) { "版本格式无效" }
        val headers = base.headers.toMutableMap()
        when (base.id) {
            "claude" -> headers["User-Agent"] = "claude-cli/" + version + " (external, cli)"
            "codex" -> {
                headers["User-Agent"] = "codex_cli_rs/" + version + " (Linux; x86_64)"
                headers["version"] = version
                headers["originator"] = "codex_cli_rs"
            }
            "google" -> {
                headers["User-Agent"] = "google-genai-sdk/" + version
                headers["x-goog-api-client"] = "google-genai-sdk/" + version
            }
            else -> return base
        }
        return base.copy(
            version = version,
            headers = headers,
            source = sanitizeCatalogSource(source),
            fetchedAt = fetchedAt,
        )
    }

    private fun publish(record: UpstreamHeaderCatalogRecord) {
        val sanitized = sanitizeRecord(record)
        state.set(
            UpstreamHeaderCatalogSnapshot(
                revision = sanitized.profiles.joinToString("|") { it.id + "@" + it.version },
                checkedAt = sanitized.checkedAt,
                successAt = sanitized.successAt,
                lastError = sanitized.lastError,
                refreshing = false,
                autoRefreshEnabled = autoRefreshEnabled,
                refreshIntervalMs = refreshIntervalMs,
                profiles = sanitized.profiles,
                history = sanitized.history,
                pins = sanitized.pins,
            ),
        )
    }

    private fun sanitizeRecord(record: UpstreamHeaderCatalogRecord): UpstreamHeaderCatalogRecord {
        val defaults = UpstreamHeaderProfiles.defaults.associateBy { it.id }
        val validProfiles = record.profiles.filter {
            upstreamHeaderProfileValidationError(it) == null
        }.associateBy { it.id }
        var invalid = record.hasCorruptRecords || record.profiles.any { upstreamHeaderProfileValidationError(it) != null }
        val profiles = PROFILE_ORDER.map { id ->
            val profile = validProfiles[id] ?: defaults.getValue(id)
            profile.copy(source = sanitizeCatalogSource(profile.source))
        }
        val history = record.history.mapValues { (id, values) ->
            values.filter {
                it.id == id && upstreamHeaderProfileValidationError(it) == null
            }.map { it.copy(source = sanitizeCatalogSource(it.source)) }
        }.filterValues { it.isNotEmpty() }
        if (history.values.sumOf { it.size } != record.history.values.sumOf { it.size }) invalid = true
        val validVersions = history.mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.version } }
        val pins = record.pins.filter { (id, version) ->
            id in PROFILE_ORDER && version in validVersions[id].orEmpty()
        }
        if (pins.size != record.pins.size) invalid = true
        val errors = buildList {
            sanitizeStoredError(record.lastError)?.let(::add)
            if (invalid) add("catalog:invalid_persisted_profile")
        }.distinct()
        return record.copy(
            profiles = profiles,
            history = history,
            pins = pins,
            lastError = errors.joinToString("; ").takeIf { it.isNotBlank() },
        )
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    private companion object {
        val PROFILE_ORDER = listOf("claude", "codex", "google", "antigravity")
        val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,99}")
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        fun defaultVersionSources(): List<HeaderProfileVersionSource> = listOf(
            HeaderProfileVersionSource("claude", "npm:@anthropic-ai/claude-code") {
                fetchNpmVersion(it, "https://registry.npmjs.org/@anthropic-ai/claude-code/latest")
            },
            HeaderProfileVersionSource("codex", "github:openai/codex") {
                fetchCodexVersion(it)
            },
            HeaderProfileVersionSource("google", "npm:@google/genai") {
                fetchNpmVersion(it, "https://registry.npmjs.org/@google/genai/latest")
            },
        )

        suspend fun fetchNpmVersion(client: HttpClient, url: String): String =
            client.prepareGet(url) {
                header(HttpHeaders.UserAgent, "PrismLLM-header-catalog/1.0")
                timeout { requestTimeoutMillis = 15_000 }
            }.execute { response ->
                if (!response.status.isSuccess()) throw HeaderCatalogHttpException(response.status.value)
                parseNpmVersionResponse(readLimitedText(response))
            }

        suspend fun fetchCodexVersion(client: HttpClient): String =
            client.prepareGet("https://api.github.com/repos/openai/codex/releases/latest") {
                header(HttpHeaders.UserAgent, "PrismLLM-header-catalog/1.0")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                timeout { requestTimeoutMillis = 15_000 }
            }.execute { response ->
                if (!response.status.isSuccess()) throw HeaderCatalogHttpException(response.status.value)
                parseCodexReleaseTag(readLimitedText(response))
            }

        suspend fun readLimitedText(response: HttpResponse): String {
            val bytes = response.bodyAsChannel().readRemaining(MAX_SOURCE_BYTES.toLong() + 1L).readBytes()
            return decodeCatalogSource(bytes)
        }

        fun safeErrorCategory(cause: Throwable): String = when (cause) {
            is HeaderCatalogHttpException -> "http_" + cause.status
            is java.net.SocketTimeoutException,
            is java.net.http.HttpTimeoutException,
            is io.ktor.client.plugins.HttpRequestTimeoutException,
            -> "timeout"
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.io.IOException,
            -> "request_failed"
            else -> "invalid_response"
        }

        fun sanitizeStoredError(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val safe = value.split("; ").filter { part ->
                SAFE_ERROR.matches(part) ||
                    part == "catalog:invalid_persisted_profile" ||
                    part == "catalog:version_conflict"
            }
            return safe.joinToString("; ").takeIf { it.isNotBlank() } ?: "catalog:stored_error_redacted"
        }

        val SAFE_ERROR = Regex(
            "(?:claude|codex|google|antigravity|manifest):" +
                "(?:http_[0-9]{3}|timeout|request_failed|invalid_response|version_conflict)|refresh_failed",
        )
    }
}

internal fun decodeCatalogSource(bytes: ByteArray): String {
    require(bytes.size <= MAX_SOURCE_BYTES) { "响应超过 1MB" }
    return bytes.toString(Charsets.UTF_8)
}

internal fun sanitizeCatalogSource(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "manifest"
    if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
        return runCatching {
            val uri = URI(trimmed)
            buildString {
                append(uri.scheme)
                append("://")
                append(uri.host ?: "manifest")
                append(uri.path.orEmpty())
            }.take(500)
        }.getOrDefault("manifest")
    }
    return when {
        trimmed == "builtin" -> "builtin"
        trimmed == "manifest" -> "manifest"
        SAFE_CATALOG_SOURCE.matches(trimmed) -> trimmed
        else -> "custom"
    }
}

private val SAFE_CATALOG_SOURCE = Regex("(?:npm|github):[0-9A-Za-z@][0-9A-Za-z._/@+-]{0,199}")

private const val MAX_SOURCE_BYTES = 1_048_576

internal fun upstreamHeaderProfileValidationError(profile: UpstreamHeaderProfile): String? {
    if (profile.id !in listOf("claude", "codex", "google", "antigravity")) return "未知 profile"
    if (profile.name.isBlank() || profile.name.length > 100) return "profile 名称无效"
    if (!Regex("[0-9A-Za-z][0-9A-Za-z._+-]{0,99}").matches(profile.version)) return "profile 版本格式无效"
    if (profile.headers.isEmpty() || profile.headers.size > 64) return "profile 请求头数量无效"
    if (profile.headers.keys.none { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }) {
        return "profile 必须包含 User-Agent"
    }
    val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    if (profile.headers.keys.map(String::lowercase).distinct().size != profile.headers.size) {
        return "profile 请求头名称重复"
    }
    profile.headers.forEach { (name, value) ->
        if (name.length !in 1..128 || !headerName.matches(name)) return "profile 请求头名称无效"
        if (name.lowercase() in MANAGED_HEADERS) return "profile 不能覆盖网关管理请求头"
        if (!validHeaderValue(value)) return "profile 请求头值无效"
    }
    return null
}

internal fun parseNpmVersionResponse(text: String): String =
    Json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.contentOrNull
        ?: error("响应缺少 version")

internal fun parseCodexReleaseTag(text: String): String {
    val tag = Json.parseToJsonElement(text).jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull
        ?: error("响应缺少 tag_name")
    return tag.removePrefix("rust-v").removePrefix("v")
}
