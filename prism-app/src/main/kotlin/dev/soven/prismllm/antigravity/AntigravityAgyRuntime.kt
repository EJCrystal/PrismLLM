package dev.soven.prismllm.antigravity

import dev.soven.prismllm.admin.domain.AntigravityAgySettingsRecord
import dev.soven.prismllm.admin.persistence.AntigravityAgySettingsRepository
import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.GatewayError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
internal data class AntigravityAgySettingsSnapshot(
    val enabled: Boolean,
    val proxy: String,
    val username: String? = null,
    val hasAuthentication: Boolean = false,
    val status: String,
    val missingDependencies: List<String> = emptyList(),
    val error: String? = null,
    val updatedAt: Long = 0,
)

internal class AntigravityAgyRuntime(
    private val defaults: AntigravityAgyConfig,
    private val repository: AntigravityAgySettingsRepository,
    private val onAuthenticated: suspend (AntigravityAgyAccount) -> Unit,
    private val prepare: suspend (AntigravityAgyConfig, (String, List<String>) -> Unit, () -> Boolean) -> Unit =
        AntigravityAgyEnvironment()::prepare,
) : AutoCloseable {
    private data class Services(val login: AntigravityAgyLoginService, val gateway: AntigravityAgyGateway) : AutoCloseable {
        override fun close() {
            login.close()
            gateway.close()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Mutex()
    private val monitor = Any()
    private var preparation: Job? = null
    private var phase = "CHECKING"

    @Volatile
    private var preparing = false

    @Volatile
    private var proxyPassword: String? = null

    @Volatile
    private var proxyCredentialsUnreadable = false

    @Volatile
    private var current = AntigravityAgySettingsSnapshot(enabled = false, proxy = defaults.proxy, status = "DISABLED")

    @Volatile
    private var services: Services? = null

    val login: AntigravityAgyLoginService? get() = if (current.enabled && current.status == "READY") services?.login else null
    val gateway: AntigravityAgyGateway? get() = if (current.enabled && current.status == "READY") services?.gateway else null
    fun snapshot(): AntigravityAgySettingsSnapshot = current

    suspend fun start() {
        val saved = repository.load()
        if (saved != null) {
            synchronized(monitor) {
                proxyPassword = saved.proxyPassword
                proxyCredentialsUnreadable = saved.proxyCredentialsUnreadable
                current = current.copy(
                    enabled = saved.enabled,
                    proxy = saved.proxy,
                    username = saved.proxyUsername,
                    hasAuthentication = saved.proxyUsername != null,
                    updatedAt = saved.updatedAt,
                    status = if (saved.enabled && saved.proxyCredentialsUnreadable) "FAILED" else current.status,
                    error = if (saved.enabled && saved.proxyCredentialsUnreadable) "代理认证凭据无法解密，请重新填写密码或关闭认证" else null,
                )
            }
            if (saved.enabled && !saved.proxyCredentialsUnreadable) launchPreparation()
        }
    }

    suspend fun update(
        enabled: Boolean,
        proxy: String,
        username: String? = null,
        password: String? = null,
        clearAuthentication: Boolean = false,
    ): AntigravityAgySettingsSnapshot = changes.withLock {
        val normalizedProxy = proxy.trim()
        if (clearAuthentication && (!username.isNullOrEmpty() || !password.isNullOrEmpty())) {
            throw badRequest("invalid_agy_proxy_authentication", "不能同时填写和清除代理认证信息")
        }
        if (!clearAuthentication && normalizedProxy.isNotEmpty() && username != null && username.isBlank()) {
            throw badRequest("invalid_agy_proxy_authentication", "代理账号不能为空；如不启用认证，请关闭账号密码认证")
        }
        val nextAuthentication = when {
            clearAuthentication || normalizedProxy.isEmpty() -> null to null
            !password.isNullOrEmpty() -> (username?.takeIf(String::isNotBlank) ?: current.username) to password
            current.hasAuthentication -> {
                if (normalizedProxy != current.proxy || proxyCredentialsUnreadable) {
                    throw badRequest("invalid_agy_proxy_authentication", "代理地址已变更，请重新填写代理密码或关闭认证")
                }
                (username ?: current.username) to proxyPassword
            }
            !username.isNullOrEmpty() -> throw badRequest("invalid_agy_proxy_authentication", "填写代理账号时必须同时填写密码")
            else -> null to null
        }
        val nextUsername = nextAuthentication.first
        val nextPassword = nextAuthentication.second
        if (nextPassword != null && nextUsername == null) {
            throw badRequest("invalid_agy_proxy_authentication", "启用代理认证时必须填写代理账号和密码")
        }
        if (nextUsername != null && nextPassword == null) {
            throw badRequest("invalid_agy_proxy_authentication", "启用代理认证时必须填写代理密码")
        }
        try {
            defaults.copy(proxy = normalizedProxy, proxyUsername = nextUsername, proxyPassword = nextPassword)
        } catch (failure: IllegalArgumentException) {
            throw badRequest("invalid_agy_proxy", "agy 代理地址须为 HTTP 主机和端口；账号密码请分开填写，代理可留空以直连")
        }
        if (preparing && (normalizedProxy != current.proxy || nextUsername != current.username ||
                nextPassword != proxyPassword || enabled && !current.enabled)) {
            throw GatewayError(HttpStatusCode.Conflict, "agy_preparation_busy", "运行环境正在准备或停止，请等待完成后再修改代理或重新开启", "invalid_request_error")
        }
        withContext(NonCancellable) {
            val saved = repository.save(AntigravityAgySettingsRecord(enabled, normalizedProxy, nextUsername, nextPassword))
            val unchanged = enabled && current.enabled && current.proxy == normalizedProxy &&
                current.username == nextUsername && proxyPassword == nextPassword && current.status == "READY"
            synchronized(monitor) {
                proxyPassword = saved.proxyPassword
                proxyCredentialsUnreadable = false
                current = current.copy(
                    enabled = enabled,
                    proxy = normalizedProxy,
                    username = saved.proxyUsername,
                    hasAuthentication = saved.proxyUsername != null,
                    updatedAt = saved.updatedAt,
                    error = null,
                )
            }
            if (!unchanged) {
                val previous = services
                services = null
                previous?.close()
                if (preparing) {
                    synchronized(monitor) { current = current.copy(status = if (enabled) phase else "STOPPING") }
                } else if (enabled) {
                    launchPreparation()
                } else {
                    synchronized(monitor) { current = current.copy(status = "DISABLED", missingDependencies = emptyList()) }
                }
            }
            current
        }
    }

    private fun launchPreparation() {
        synchronized(monitor) {
            preparing = true
            phase = "CHECKING"
            current = current.copy(status = phase, error = null, missingDependencies = emptyList())
        }
        preparation = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val config = defaults.copy(
                    proxy = current.proxy,
                    proxyUsername = current.username.takeIf { current.hasAuthentication },
                    proxyPassword = proxyPassword.takeIf { current.hasAuthentication },
                )
                prepare(config, { status, missing ->
                    synchronized(monitor) {
                        phase = status
                        current = current.copy(status = if (current.enabled) status else "STOPPING", missingDependencies = missing)
                    }
                }, { current.enabled })
                changes.withLock {
                    synchronized(monitor) {
                        preparing = false
                        if (current.enabled) {
                            services = newServices(config)
                            current = current.copy(status = "READY", error = null)
                        } else {
                            current = current.copy(status = "DISABLED", missingDependencies = emptyList())
                        }
                    }
                }
            } catch (failure: CancellationException) {
                preparing = false
                throw failure
            } catch (failure: Exception) {
                changes.withLock {
                    synchronized(monitor) {
                        preparing = false
                        current = current.copy(status = if (current.enabled) "FAILED" else "DISABLED",
                            error = if (current.enabled) (failure as? AgyPreparationException)?.message ?: "运行环境准备失败，请检查服务端安装权限、代理和磁盘空间后重试" else null)
                    }
                }
            }
        }.also { it.start() }
    }

    private fun newServices(config: AntigravityAgyConfig) = Services(
        AntigravityAgyLoginService(config, onAuthenticated = onAuthenticated), AntigravityAgyGateway(config),
    )

    override fun close() {
        scope.cancel()
        runBlocking { preparation?.cancelAndJoin() }
        services?.close()
        services = null
    }
}
