package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.domain.OAuthAccountRecord
import dev.soven.llmgateway.domain.OAuthProvider
import dev.soven.llmgateway.domain.OAuthQuotaSnapshot
import dev.soven.llmgateway.domain.OAuthQuotaWindow
import dev.soven.llmgateway.domain.saturatedAdd
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.proxy.codeassist.CodeAssistProjectResolver
import dev.soven.llmgateway.proxy.codeassist.CodeAssistQuotaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 管理面手动读取额度的结果。 */
sealed interface OAuthQuotaResult {
    data class Refreshed(val account: OAuthAccountRecord) : OAuthQuotaResult

    data class Failed(val errorCode: String) : OAuthQuotaResult

    /** 提供方不支持额度查询，或账号还没有可用的 access token。 */
    data object NotSupported : OAuthQuotaResult

    data object NotFound : OAuthQuotaResult
}

/**
 * 上游额度观测：读取 ChatGPT 的 `/wham/usage`，把 5 小时与每周窗口的使用比例、
 * 重置时间、credits 与可重置额度写入账号表，并同步给调度器。
 *
 * 约束：
 * - 只读取，不消耗上游额度；每次读数都走账号绑定的同一个代理出口。
 * - 读取失败保留上一次成功读数，只记录脱敏后的错误码。
 * - 401 时强制刷新一次 access token 再重试，其余失败按错误码返回。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthQuotaService(
    private val repository: OAuthAccountRepository,
    private val tokenService: OAuthTokenService,
    private val config: OAuthConfig,
    private val projectResolver: CodeAssistProjectResolver,
    private val antigravityQuota: CodeAssistQuotaClient,
    private val onQuota: suspend (String, OAuthQuotaSnapshot) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(OAuthQuotaService::class.java)
    private val worker = AtomicReference<Job?>(null)

    /** 启动后台扫描。首次扫描延迟一个周期，避免启动期与 token 刷新抢占。 */
    fun start(scope: CoroutineScope) {
        if (worker.get() != null) return
        val job = scope.launch {
            while (isActive) {
                delay(config.quotaIntervalMs)
                try {
                    sweep()
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    logger.warn("oauth quota sweep failed: {}", cause::class.simpleName)
                }
            }
        }
        if (!worker.compareAndSet(null, job)) job.cancel()
    }

    override fun close() {
        worker.getAndSet(null)?.cancel()
    }

    suspend fun refreshNow(accountId: String): OAuthQuotaResult {
        val account = repository.find(accountId) ?: return OAuthQuotaResult.NotFound
        return refreshAccount(account)
    }

    private suspend fun refreshAccount(account: OAuthAccountRecord): OAuthQuotaResult {
        if (account.provider == OAuthProvider.ANTIGRAVITY) return refreshAntigravity(account)
        if (account.provider != OAuthProvider.CHATGPT) return OAuthQuotaResult.NotSupported
        val usageUrl = config.chatGpt.usageUrl ?: return OAuthQuotaResult.NotSupported
        val token = try {
            tokenService.accessToken(account.id)
        } catch (cause: OAuthTokenUnavailableException) {
            return failed(account.id, cause.errorCode ?: "credential_unavailable")
        } ?: return OAuthQuotaResult.NotFound

        val claims = parseOAuthTokenClaims(token.accessToken)
        val accountId = account.chatgptAccountId ?: claims?.chatgptAccountId
            ?: return failed(account.id, "account_id_missing")
        if (account.chatgptAccountId != accountId) {
            repository.updateChatgptAccountId(account.id, accountId)
        }

        val usage = try {
            readUsage(usageUrl, token.accessToken, accountId, account.proxyUrl)
        } catch (cause: QuotaProbeException) {
            if (cause.status != 401) return failed(account.id, cause.errorCode)
            // access token 可能刚被上游吊销：强制刷新一次再试。
            val refreshed = try {
                tokenService.accessToken(account.id, forceRefresh = true)
            } catch (refreshCause: OAuthTokenUnavailableException) {
                return failed(account.id, refreshCause.errorCode ?: "credential_unavailable")
            } ?: return OAuthQuotaResult.NotFound
            try {
                readUsage(usageUrl, refreshed.accessToken, accountId, account.proxyUrl)
            } catch (retryCause: QuotaProbeException) {
                return failed(account.id, retryCause.errorCode)
            }
        }

        val snapshot = OAuthQuotaSnapshot(
            primary = usage.primary,
            secondary = usage.secondary,
            allowed = usage.allowed,
            creditsBalance = usage.creditsBalance,
            creditsUnlimited = usage.creditsUnlimited,
            resetCredits = usage.resetCredits,
            checkedAt = now(),
        )
        repository.saveQuota(account.id, snapshot, usage.planType)
        onQuota(account.id, snapshot)
        logger.debug(
            "oauth quota read, accountId={}, primary={}, secondary={}, allowed={}",
            account.id,
            usage.primary?.usedPercent,
            usage.secondary?.usedPercent,
            usage.allowed,
        )
        return OAuthQuotaResult.Refreshed(repository.findDetailed(account.id) ?: account)
    }

    /** Antigravity 的额度来自 Code Assist 的 retrieveUserQuotaSummary。 */
    private suspend fun refreshAntigravity(account: OAuthAccountRecord): OAuthQuotaResult {
        val token = try {
            tokenService.accessToken(account.id)
        } catch (cause: OAuthTokenUnavailableException) {
            return failed(account.id, cause.errorCode ?: "credential_unavailable")
        } ?: return OAuthQuotaResult.NotFound
        val project = projectResolver.context(account, token.accessToken).project
            ?: return failed(account.id, "project_missing")
        val snapshot = antigravityQuota.read(token.accessToken, project)
            ?: return failed(account.id, "quota_read_failed")
        repository.saveQuota(account.id, snapshot)
        onQuota(account.id, snapshot)
        return OAuthQuotaResult.Refreshed(repository.findDetailed(account.id) ?: account)
    }

    private suspend fun failed(accountId: String, errorCode: String): OAuthQuotaResult {
        repository.saveQuotaError(accountId, errorCode, now())
        logger.debug("oauth quota read failed, accountId={}, error={}", accountId, errorCode)
        return OAuthQuotaResult.Failed(errorCode)
    }

    private suspend fun sweep() {
        val due = repository.listDueForQuota(now() - config.quotaIntervalMs)
        if (due.isEmpty()) return
        val refreshed = AtomicInteger()
        val failed = AtomicInteger()
        val semaphore = Semaphore(MAX_CONCURRENT_QUOTA_READS)
        coroutineScope {
            due.map { account ->
                async {
                    semaphore.withPermit {
                        // 单个账号的意外异常不能中断整轮扫描。
                        try {
                            when (refreshAccount(account)) {
                                is OAuthQuotaResult.Refreshed -> refreshed.incrementAndGet()
                                is OAuthQuotaResult.Failed -> failed.incrementAndGet()
                                OAuthQuotaResult.NotSupported, OAuthQuotaResult.NotFound -> Unit
                            }
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: Throwable) {
                            failed.incrementAndGet()
                            logger.warn(
                                "oauth quota read failed, accountId={}, error={}",
                                account.id,
                                cause::class.simpleName,
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        if (refreshed.get() > 0 || failed.get() > 0) {
            logger.info("oauth quota sweep: refreshed={}, failed={}", refreshed.get(), failed.get())
        }
    }

    private suspend fun readUsage(
        url: String,
        accessToken: String,
        accountId: String,
        proxyUrl: String?,
    ): CodexUsage {
        val proxy = try {
            proxyAddress(proxyUrl, config.proxyAllowedHosts)
        } catch (cause: OAuthRefreshException) {
            throw QuotaProbeException(cause.errorCode)
        }
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(minOf(config.requestTimeoutMs, 10_000)))
            .followRedirects(HttpClient.Redirect.NEVER)
            .apply { proxy?.let { this.proxy(ProxySelector.of(it)) } }
            .build()
        try {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMillis(config.requestTimeoutMs))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .header("chatgpt-account-id", accountId)
                .header("originator", ORIGINATOR)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
            val response = try {
                sendBounded(client, request)
            } catch (_: HttpTimeoutException) {
                throw QuotaProbeException("timeout")
            } catch (cause: InterruptedException) {
                Thread.currentThread().interrupt()
                throw QuotaProbeException("interrupted")
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                throw QuotaProbeException("network_error")
            }
            val body = String(response.body(), StandardCharsets.UTF_8)
            if (response.statusCode() !in 200..299) {
                throw QuotaProbeException("http_${response.statusCode()}", response.statusCode())
            }
            return parseChatGptUsage(body, now()) ?: throw QuotaProbeException("invalid_response")
        } finally {
            client.shutdownNow()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_QUOTA_READS = 4
        const val ORIGINATOR = "codex_cli_rs"
        const val USER_AGENT = "codex_cli_rs/0.162.0 (Linux; x86_64) xterm-256color"
    }
}

/** 一次额度读取失败。[status] 只用于 401 重试判断，不写日志。 */
private class QuotaProbeException(
    val errorCode: String,
    val status: Int? = null,
) : RuntimeException(errorCode)

/** `/wham/usage` 解析后的额度。 */
internal data class CodexUsage(
    val planType: String? = null,
    val allowed: Boolean? = null,
    val primary: OAuthQuotaWindow? = null,
    val secondary: OAuthQuotaWindow? = null,
    val creditsBalance: Double? = null,
    val creditsUnlimited: Boolean = false,
    val resetCredits: Int? = null,
)

/**
 * 解析 ChatGPT 的额度响应。字段名与 Codex 客户端一致：
 * `rate_limit.primary_window` / `secondary_window`、`credits`、`rate_limit_reset_credits`。
 * 响应不是 JSON 对象时返回 null。
 */
internal fun parseChatGptUsage(body: String, now: Long): CodexUsage? {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val rateLimit = root["rate_limit"] as? JsonObject
    val credits = root["credits"] as? JsonObject
    val balance = (credits?.get("balance") as? JsonPrimitive)?.let { primitive ->
        primitive.doubleOrNull ?: primitive.contentOrNull?.trim()?.toDoubleOrNull()
    }
    val resetCredits = ((root["rate_limit_reset_credits"] as? JsonObject)?.get("available_count") as? JsonPrimitive)
        ?.intOrNull
        ?.takeIf { it >= 0 }
    return CodexUsage(
        planType = (root["plan_type"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        allowed = (rateLimit?.get("allowed") as? JsonPrimitive)?.booleanOrNull,
        primary = parseQuotaWindow(rateLimit?.get("primary_window") as? JsonObject, now),
        secondary = parseQuotaWindow(rateLimit?.get("secondary_window") as? JsonObject, now),
        creditsBalance = balance?.takeIf { it >= 0 },
        creditsUnlimited = (credits?.get("unlimited") as? JsonPrimitive)?.booleanOrNull == true,
        resetCredits = resetCredits,
    )
}

private fun parseQuotaWindow(window: JsonObject?, now: Long): OAuthQuotaWindow? {
    if (window == null) return null
    val used = (window["used_percent"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val seconds = (window["limit_window_seconds"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 1..MAX_WINDOW_SECONDS }
        ?.toInt()
    val resetAt = (window["reset_at"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 1..MAX_EPOCH_SECONDS }
        ?.let { it * 1_000 }
    val resetAfter = (window["reset_after_seconds"] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it in 1..MAX_WINDOW_SECONDS }
        ?.let { saturatedAdd(now, it * 1_000) }
    return OAuthQuotaWindow(
        usedPercent = used.coerceIn(0.0, 100.0),
        windowSeconds = seconds,
        resetsAt = resetAt ?: resetAfter,
    )
}

/** 一年，避免上游返回异常大的窗口时长。 */
internal const val MAX_WINDOW_SECONDS = 31_536_000L

/** 2100 年，避免 reset_at 溢出或误把毫秒当秒。 */
internal const val MAX_EPOCH_SECONDS = 4_102_444_800L
