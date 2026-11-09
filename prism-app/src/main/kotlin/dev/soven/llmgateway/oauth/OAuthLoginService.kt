package dev.soven.llmgateway.oauth

import dev.soven.llmgateway.config.OAuthConfig
import dev.soven.llmgateway.oauth.domain.OAuthAccountRecord
import dev.soven.llmgateway.oauth.domain.OAuthProviderConfig
import dev.soven.llmgateway.oauth.domain.parseOAuthTokenClaims
import dev.soven.llmgateway.oauth.infra.OAuthRefreshException
import dev.soven.llmgateway.oauth.infra.postTokenRequest
import dev.soven.llmgateway.shared.domain.OAuthProvider
import dev.soven.llmgateway.channel.domain.ProviderEndpoints
import dev.soven.llmgateway.shared.domain.saturatedAdd
import dev.soven.llmgateway.repo.CredentialChangedException
import dev.soven.llmgateway.repo.NewOAuthAccount
import dev.soven.llmgateway.repo.OAuthAccountRepository
import dev.soven.llmgateway.repo.OAuthAccountCredentialUpdate
import dev.soven.llmgateway.shared.crypto.CredentialCipher
import dev.soven.llmgateway.security.credentialFingerprint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 一次交互式登录的发起结果。授权地址只包含公开参数，不包含任何凭据。 */
sealed interface OAuthLoginStartResult {
    data class Started(
        val loginId: String,
        val authorizeUrl: String,
        val redirectUri: String,
        val expiresAt: Long,
    ) : OAuthLoginStartResult

    /** 提供方未登记授权地址与回调地址，只能使用 refresh token 导入。 */
    data object UnsupportedProvider : OAuthLoginStartResult
}

/** 交互式登录的完成结果。 */
sealed interface OAuthLoginResult {
    data class Completed(val account: OAuthAccountRecord) : OAuthLoginResult

    data class Failed(val errorCode: String) : OAuthLoginResult

    /** 登录会话不存在、已被使用或已过期。 */
    data object Expired : OAuthLoginResult

    /** 回调地址缺少 code/state，或 state 与本次登录不匹配。 */
    data class InvalidRequest(val reason: String) : OAuthLoginResult

    /** 登录期间目标账号被删除或替换了凭据，不能把授权结果写到新目标上。 */
    data object StaleTarget : OAuthLoginResult

    data object UnsupportedProvider : OAuthLoginResult
}

/** 绑定阶段的结果：同一登录会话最多只有一个请求能真正写库。 */
internal sealed interface LoginBinding<out T> {
    data class Bound<T>(val value: T) : LoginBinding<T>

    /** 另一个请求正在绑定，本次按已使用处理。 */
    data object Busy : LoginBinding<Nothing>

    /** 会话已经成功绑定过，晚到的并发请求不能再次写库。 */
    data object Consumed : LoginBinding<Nothing>
}

/**
 * 交互式 OAuth 登录：在网关内生成 PKCE 授权链接，管理员在浏览器完成登录后
 * 把回调地址粘回管理面，由网关用 code + verifier 换取 token 并直接绑定账号。
 *
 * 约束：
 * - 登录会话只保存在内存，10 分钟有效、单次使用；网关重启后需要重新发起。
 * - code、verifier 与任何 token 都不写日志、不返回给浏览器，只把最终账号信息写入库。
 * - 回调地址只解析 query 参数；state 必须与本次会话一致，防止把其他登录的 code 串进来。
 * - 换取 token 使用账号绑定的同一个代理出口。
 * - 授权码只能使用一次：交换成功后 token 短暂留在会话里，绑定失败（如邮箱冲突）时
 *   管理员处理完冲突可用同一 loginId 重试；绑定成功或会话过期立即释放。
 *
 * @author 文杰
 * @since 2026-11-06
 */
class OAuthLoginService(
    private val repository: OAuthAccountRepository,
    private val cipher: CredentialCipher,
    private val config: OAuthConfig,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val logger = LoggerFactory.getLogger(OAuthLoginService::class.java)
    private val pending = ConcurrentHashMap<String, PendingLogin>()

    fun start(
        provider: OAuthProvider,
        accountId: String?,
        name: String?,
        channelIds: Collection<String>,
        proxyUrl: String?,
        credentialFingerprint: String?,
    ): OAuthLoginStartResult {
        val providerConfig = providerConfig(provider).takeIf { it.supportsLogin }
            ?: return OAuthLoginStartResult.UnsupportedProvider
        prune()
        if (pending.size >= MAX_PENDING_LOGINS) {
            pending.entries.minByOrNull { it.value.createdAt }?.let { pending.remove(it.key) }
        }
        val verifier = randomToken(48)
        val state = randomToken(24)
        val loginId = randomToken(18)
        val expiresAt = saturatedAdd(now(), LOGIN_TTL_MS)
        pending[loginId] = PendingLogin(
            provider = provider,
            accountId = accountId,
            name = name,
            channelIds = channelIds,
            proxyUrl = proxyUrl,
            credentialFingerprint = credentialFingerprint,
            verifier = verifier,
            state = state,
            redirectUri = providerConfig.redirectUri!!,
            createdAt = now(),
            expiresAt = expiresAt,
        )
        val query = buildList {
            add("response_type" to "code")
            add("client_id" to providerConfig.clientId)
            add("redirect_uri" to providerConfig.redirectUri!!)
            providerConfig.loginScopes?.let { add("scope" to it) }
            add("code_challenge" to challenge(verifier))
            add("code_challenge_method" to "S256")
            add("state" to state)
            when (provider) {
                OAuthProvider.CHATGPT -> {
                    // 与 Codex CLI 的登录请求保持一致，否则 OpenAI 会拒绝这个客户端。
                    add("id_token_add_organizations" to "true")
                    add("codex_cli_simplified_flow" to "true")
                    add("originator" to ORIGINATOR)
                }
                OAuthProvider.ANTIGRAVITY -> {
                    // Google 默认不返回 refresh_token，必须显式要求离线访问并重新征得同意。
                    add("access_type" to "offline")
                    add("prompt" to "consent")
                    add("include_granted_scopes" to "true")
                }
            }
        }
        val authorizeUrl = providerConfig.authorizeUrl!! + "?" + query.joinToString("&") { (name, value) ->
            encode(name) + "=" + encode(value)
        }
        return OAuthLoginStartResult.Started(loginId, authorizeUrl, providerConfig.redirectUri!!, expiresAt)
    }

    suspend fun complete(loginId: String, callback: String): OAuthLoginResult {
        prune()
        val session = pending[loginId] ?: return OAuthLoginResult.Expired
        if (session.expiresAt <= now()) {
            pending.remove(loginId)
            return OAuthLoginResult.Expired
        }
        val parsed = parseCallback(callback)
            ?: return OAuthLoginResult.InvalidRequest("回调地址中缺少 code 或 state")
        if (parsed.error != null) {
            pending.remove(loginId)
            return OAuthLoginResult.Failed(sanitizeError(parsed.error))
        }
        val code = parsed.code ?: return OAuthLoginResult.InvalidRequest("回调地址中缺少 code")
        if (parsed.state != session.state) {
            return OAuthLoginResult.InvalidRequest("state 不匹配，请重新生成登录链接")
        }
        // 登录期间目标账号被删除或换过凭据时拒绝绑定，避免覆盖管理员刚导入的新凭据。
        val target = session.accountId?.let { accountId ->
            repository.find(accountId)?.takeIf { account ->
                session.credentialFingerprint == null ||
                    account.credentialFingerprint() == session.credentialFingerprint
            } ?: run {
                pending.remove(loginId)
                return OAuthLoginResult.StaleTarget
            }
        }

        // 授权码只能交换一次：成功后把 token 留在会话里，绑定失败时可用同一 loginId 重试。
        val tokens = session.tokens ?: run {
            if (!session.claimed.compareAndSet(false, true)) return OAuthLoginResult.Expired
            val providerConfig = providerConfig(session.provider)
            if (!providerConfig.supportsLogin) {
                pending.remove(loginId)
                return OAuthLoginResult.UnsupportedProvider
            }
            val exchanged = try {
                exchange(providerConfig, session, code)
            } catch (cause: OAuthRefreshException) {
                // code 已消耗，无法重试；重新发起登录即可。
                pending.remove(loginId)
                return OAuthLoginResult.Failed(cause.errorCode)
            }
            session.tokens = exchanged
            session.tokenExpiresAt = saturatedAdd(now(), exchanged.expiresInSeconds * 1_000)
            exchanged
        }
        val refreshToken = tokens.refreshToken
        if (refreshToken == null) {
            pending.remove(loginId)
            return OAuthLoginResult.Failed("missing_refresh_token")
        }
        val accessToken = tokens.accessToken
        if (accessToken == null) {
            pending.remove(loginId)
            return OAuthLoginResult.Failed("missing_access_token")
        }
        // 绑定阶段互斥：并发提交只允许一个请求写库；成功绑定后会话永久消耗，
        // 已经持有会话引用的晚到请求不会再写一次库或重复记审计。
        val binding = session.bindOnce {
            val claims = parseOAuthTokenClaims(tokens.idToken) ?: parseOAuthTokenClaims(accessToken)
            val account = try {
                if (target != null) {
                    repository.updateCredentials(
                        accountId = target.id,
                        input = OAuthAccountCredentialUpdate(
                            provider = session.provider,
                            accountEmail = claims?.email?.take(MAX_EMAIL_CHARS),
                            accountPlan = claims?.planType?.take(MAX_PLAN_CHARS),
                            proxyUrl = session.proxyUrl,
                            refreshToken = cipher.encrypt(refreshToken),
                            accessToken = cipher.encrypt(accessToken),
                            accessTokenExpiresAt = session.tokenExpiresAt,
                            tokenType = "Bearer",
                            scope = tokens.scope?.take(MAX_SCOPE_CHARS),
                            expectedCredentialFingerprint = session.credentialFingerprint,
                        ),
                    ) ?: return@bindOnce null
                } else {
                    repository.create(
                        NewOAuthAccount(
                            name = session.name ?: claims?.email?.take(MAX_EMAIL_CHARS) ?: providerLabel(session.provider),
                            provider = session.provider,
                            accountEmail = claims?.email?.take(MAX_EMAIL_CHARS),
                            accountPlan = claims?.planType?.take(MAX_PLAN_CHARS),
                            refreshToken = cipher.encrypt(refreshToken),
                            accessToken = cipher.encrypt(accessToken),
                            accessTokenExpiresAt = session.tokenExpiresAt,
                            tokenType = "Bearer",
                            scope = tokens.scope?.take(MAX_SCOPE_CHARS),
                            proxyUrl = session.proxyUrl,
                            channelIds = session.channelIds,
                            providerChannelPending = ProviderEndpoints.of(session.provider).servable,
                        ),
                    )
                }
            } catch (_: CredentialChangedException) {
                // 校验与写入在同一事务内；凭据在预检查之后又被替换时按目标失效处理。
                pending.remove(loginId)
                return@bindOnce null
            }
            claims?.chatgptAccountId?.take(MAX_ACCOUNT_ID_CHARS)?.let { accountId ->
                repository.updateChatgptAccountId(account.id, accountId)
            }
            if (session.accountId != null && session.channelIds.isNotEmpty()) {
                repository.setChannels(account.id, session.channelIds)
            }
            pending.remove(loginId)
            logger.info(
                "oauth interactive login completed, accountId={}, provider={}, plan={}",
                account.id,
                session.provider.value,
                claims?.planType,
            )
            OAuthLoginResult.Completed(repository.findDetailed(account.id) ?: account)
        }
        return when (binding) {
            LoginBinding.Busy, LoginBinding.Consumed -> OAuthLoginResult.Expired
            is LoginBinding.Bound -> binding.value ?: OAuthLoginResult.StaleTarget
        }
    }

    private suspend fun exchange(
        providerConfig: OAuthProviderConfig,
        session: PendingLogin,
        code: String,
    ): LoginTokens {
        val form = buildList {
            add("grant_type" to "authorization_code")
            add("code" to code)
            add("redirect_uri" to session.redirectUri)
            add("client_id" to providerConfig.clientId)
            // Google 的安装型客户端在换取授权码时仍要求 client_secret；ChatGPT 没有 secret。
            providerConfig.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret" to it) }
            add("code_verifier" to session.verifier)
        }.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }
        val headers = if (session.provider == OAuthProvider.CHATGPT) {
            mapOf("originator" to ORIGINATOR, "User-Agent" to USER_AGENT)
        } else {
            emptyMap()
        }
        val response = postTokenRequest(
            endpoint = providerConfig.tokenEndpoint,
            contentType = "application/x-www-form-urlencoded",
            body = form,
            proxyUrl = session.proxyUrl,
            timeoutMs = config.requestTimeoutMs,
            headers = headers,
            proxyAllowedHosts = config.proxyAllowedHosts,
        )
        return response.toLoginTokens()
    }

    private fun prune() {
        val nowMs = now()
        pending.entries.removeIf { it.value.expiresAt <= nowMs }
    }

    private fun providerConfig(provider: OAuthProvider): OAuthProviderConfig = when (provider) {
        OAuthProvider.CHATGPT -> config.chatGpt
        OAuthProvider.ANTIGRAVITY -> config.antigravity
    }

    private fun providerLabel(provider: OAuthProvider): String = when (provider) {
        OAuthProvider.CHATGPT -> "ChatGPT 账号"
        OAuthProvider.ANTIGRAVITY -> "Antigravity 账号"
    }

    internal class PendingLogin(
        val provider: OAuthProvider,
        val accountId: String?,
        val name: String?,
        val channelIds: Collection<String>,
        val proxyUrl: String?,
        /** 发起登录时目标账号的凭据指纹；新账号为 null。 */
        val credentialFingerprint: String?,
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val createdAt: Long,
        val expiresAt: Long,
        val claimed: AtomicBoolean = AtomicBoolean(false),
        /** 授权码交换结果；绑定成功或会话过期后释放。 */
        @Volatile var tokens: LoginTokens? = null,
        @Volatile var tokenExpiresAt: Long = 0,
    ) {
        /** 绑定阶段的互斥；交换成功后允许在绑定失败时重试。 */
        private val binding = AtomicBoolean(false)

        /** 绑定成功标记；一旦置位不再重置，晚到的并发请求不能复用同一会话写库。 */
        private val completed = AtomicBoolean(false)

        /**
         * 在互斥保护下执行一次绑定。[block] 正常返回后会话永久消耗；
         * 抛异常时不消耗，管理员处理完冲突（如邮箱重复）可以用缓存的 token 重试。
         */
        suspend fun <T> bindOnce(block: suspend () -> T): LoginBinding<T> {
            if (!binding.compareAndSet(false, true)) return LoginBinding.Busy
            try {
                if (completed.get()) return LoginBinding.Consumed
                val value = block()
                completed.set(true)
                return LoginBinding.Bound(value)
            } finally {
                binding.set(false)
            }
        }
    }

    private companion object {
        const val LOGIN_TTL_MS = 600_000L
        const val MAX_PENDING_LOGINS = 64
        const val MAX_EMAIL_CHARS = 255
        const val MAX_PLAN_CHARS = 32
        const val MAX_SCOPE_CHARS = 512
        const val MAX_ACCOUNT_ID_CHARS = 128
        const val ORIGINATOR = "codex_cli_rs"
        const val USER_AGENT = "codex_cli_rs/0.162.0 (Linux; x86_64) xterm-256color"
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun sanitizeError(value: String): String = value
        .filter { it.isLetterOrDigit() || it in "._-" }
        .take(80)
        .ifBlank { "login_failed" }
}

/** 一次授权码交换得到的 token；只保留后续绑定账号所需的字段。 */
internal data class LoginTokens(
    val accessToken: String?,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val scope: String?,
)

private fun JsonObject.toLoginTokens(): LoginTokens = LoginTokens(
    accessToken = string("access_token"),
    refreshToken = string("refresh_token"),
    idToken = string("id_token"),
    expiresInSeconds = (this["expires_in"] as? JsonPrimitive)?.longOrNull?.coerceIn(60, 2_592_000) ?: 3_600,
    scope = string("scope"),
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 回调地址解析结果；[error] 非空表示用户在授权页拒绝或上游返回了错误。 */
internal data class ParsedCallback(
    val code: String?,
    val state: String?,
    val error: String?,
)

/**
 * 只解析回调地址中的 query 参数。支持完整地址、`?code=...` 片段和裸 query 三种粘贴形式；
 * 无法解析出 code 或 error 时返回 null。
 */
internal fun parseCallback(callback: String): ParsedCallback? {
    val text = callback.trim().take(4_096)
    if (text.isEmpty()) return null
    val query = when {
        text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) ->
            runCatching { URI(text).rawQuery }.getOrNull()
        else -> text.substringAfter('?', text)
    } ?: return null
    val params = query.split('&').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val name = runCatching { URLDecoder.decode(part.substring(0, index), StandardCharsets.UTF_8) }.getOrNull()
            ?: return@mapNotNull null
        val value = runCatching { URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8) }.getOrNull()
            ?: return@mapNotNull null
        name to value
    }.toMap()
    val error = params["error"]?.takeIf { it.isNotBlank() }
    val code = params["code"]?.takeIf { it.isNotBlank() }
    val state = params["state"]?.takeIf { it.isNotBlank() }
    if (error == null && code == null) return null
    return ParsedCallback(code, state, error)
}
