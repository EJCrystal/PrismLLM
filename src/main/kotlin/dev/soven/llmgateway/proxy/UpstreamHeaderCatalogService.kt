package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.UpstreamHeaderCatalogSnapshot
import dev.soven.llmgateway.domain.UpstreamHeaderProfile
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRecord
import dev.soven.llmgateway.repo.UpstreamHeaderCatalogRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
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
            val current = state.get().profiles.associateBy { it.id }.toMutableMap()
            val errors = mutableListOf<String>()
            var successfulSources = 0

            val sourceResults = supervisorScope {
                versionSources.map { source ->
                    async { source to capture { source.fetch(httpClient) } }
                }.awaitAll()
            }
            sourceResults.forEach { (source, result) ->
                result.onSuccess { version ->
                    val base = current[source.profileId] ?: return@onSuccess
                    current[source.profileId] = applyVersion(base, version, source.source, checkedAt)
                    successfulSources += 1
                }.onFailure { cause ->
                    errors += source.profileId + ": " + (cause.message ?: cause::class.simpleName)
                }
            }

            val catalogUrl = manifestUrl
            if (!catalogUrl.isNullOrBlank()) {
                capture { fetchManifest(catalogUrl, checkedAt) }
                    .onSuccess { profiles ->
                        profiles.forEach { current[it.id] = it }
                        successfulSources += 1
                    }
                    .onFailure { cause ->
                        errors += "manifest: " + (cause.message ?: cause::class.simpleName)
                    }
            }

            val error = errors.joinToString("; ").takeIf { it.isNotBlank() }
            val record = if (successfulSources > 0) {
                repository.saveRefresh(
                    profiles = current.values.sortedBy { PROFILE_ORDER.indexOf(it.id) },
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
            val message = cause.message ?: cause::class.simpleName ?: "刷新失败"
            logger.warn("upstream header catalog refresh failed: {}", message)
            runCatching { repository.saveFailedRefresh(now(), message) }
                .onSuccess(::publish)
                .onFailure { persistenceError ->
                    logger.warn("failed to persist header catalog refresh error", persistenceError)
                    state.updateAndGet { it.copy(refreshing = false, lastError = message.take(1_000)) }
                }
            state.get()
        }
    }

    suspend fun pin(profileId: String, version: String): UpstreamHeaderCatalogSnapshot {
        require(profileId in PROFILE_ORDER) { "未知请求头预设" }
        publish(repository.pin(profileId, version))
        return state.get()
    }

    suspend fun unpin(profileId: String): UpstreamHeaderCatalogSnapshot {
        require(profileId in PROFILE_ORDER) { "未知请求头预设" }
        publish(repository.unpin(profileId))
        return state.get()
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
            validateProfile(profile)
            profile.copy(source = profile.source.takeIf { it.isNotBlank() } ?: url, fetchedAt = checkedAt)
        }
    }

    private suspend fun fetchText(url: String): String {
        require(url.startsWith("https://")) { "请求头目录 URL 必须使用 HTTPS" }
        val response = httpClient.get(url) {
            header(HttpHeaders.UserAgent, "llm-gateway-header-catalog/1.0")
            header(HttpHeaders.Accept, "application/json")
            timeout { requestTimeoutMillis = 15_000 }
        }
        require(response.status.isSuccess()) { "HTTP " + response.status.value }
        val text = response.bodyAsText()
        require(text.length <= MAX_SOURCE_CHARS) { "响应超过 1MB" }
        return text
    }

    private fun validateProfile(profile: UpstreamHeaderProfile) {
        require(profile.id in PROFILE_ORDER) { "未知 profile: " + profile.id }
        require(profile.name.isNotBlank() && profile.name.length <= 100) { "profile 名称无效" }
        require(VERSION.matches(profile.version)) { "profile 版本格式无效" }
        require(profile.headers.isNotEmpty() && profile.headers.size <= 64) { "profile 请求头数量无效" }
        require(profile.headers.keys.any { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }) {
            "profile 必须包含 User-Agent"
        }
        profile.headers.forEach { (name, value) ->
            require(name.length in 1..128 && HEADER_NAME.matches(name)) { "profile 请求头名称无效" }
            require(name.lowercase() !in MANAGED_HEADERS) { "profile 不能覆盖网关管理请求头 " + name }
            require(validHeaderValue(value)) { "profile 请求头 " + name + " 的值无效" }
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
        return base.copy(version = version, headers = headers, source = source, fetchedAt = fetchedAt)
    }

    private fun publish(record: UpstreamHeaderCatalogRecord) {
        state.set(
            UpstreamHeaderCatalogSnapshot(
                revision = record.profiles.joinToString("|") { it.id + "@" + it.version },
                checkedAt = record.checkedAt,
                successAt = record.successAt,
                lastError = record.lastError,
                refreshing = false,
                autoRefreshEnabled = autoRefreshEnabled,
                refreshIntervalMs = refreshIntervalMs,
                profiles = record.profiles,
                history = record.history,
                pins = record.pins,
            ),
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
        const val MAX_SOURCE_CHARS = 1_048_576
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

        suspend fun fetchNpmVersion(client: HttpClient, url: String): String {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, "llm-gateway-header-catalog/1.0")
                timeout { requestTimeoutMillis = 15_000 }
            }
            require(response.status.isSuccess()) { "HTTP " + response.status.value }
            return parseNpmVersionResponse(response.bodyAsText())
        }

        suspend fun fetchCodexVersion(client: HttpClient): String {
            val response = client.get("https://api.github.com/repos/openai/codex/releases/latest") {
                header(HttpHeaders.UserAgent, "llm-gateway-header-catalog/1.0")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                timeout { requestTimeoutMillis = 15_000 }
            }
            require(response.status.isSuccess()) { "HTTP " + response.status.value }
            return parseCodexReleaseTag(response.bodyAsText())
        }
    }
}

internal fun parseNpmVersionResponse(text: String): String =
    Json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.contentOrNull
        ?: error("响应缺少 version")

internal fun parseCodexReleaseTag(text: String): String {
    val tag = Json.parseToJsonElement(text).jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull
        ?: error("响应缺少 tag_name")
    return tag.removePrefix("rust-v").removePrefix("v")
}
