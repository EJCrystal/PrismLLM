package dev.soven.prismllm.antigravity

import dev.soven.prismllm.config.AntigravityAgyConfig
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.GatewayError
import io.ktor.http.HttpStatusCode
import dev.soven.prismllm.shared.error.notFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class AntigravityAgyAccount(
    val id: String,
    val name: String,
    val sourceAccountId: String? = null,
    val subscriptionTier: String? = null,
    val currentModelId: String? = null,
    val authorizedAt: Long,
    val routingEnabled: Boolean = false,
)

@Serializable
data class AntigravityAgyLogin(
    val loginId: String,
    val state: String,
    val expiresAt: Long,
    val authorizeUrl: String? = null,
    val account: AntigravityAgyAccount? = null,
    val error: String? = null,
)

internal class AntigravityAgyLoginService(
    private val config: AntigravityAgyConfig,
    private val onAuthenticated: suspend (AntigravityAgyAccount) -> Unit = {},
    private val inspect: suspend (Path) -> AcpPilotCatalog = { state ->
        AntigravityAcpPilot(config.inspectCommand(state), state, agentWorkspace = Path.of("/workspace")).inspect()
    },
) : AutoCloseable {
    private class Session(
        val owner: String,
        val profileId: String,
        val name: String,
        val sourceAccountId: String?,
        var snapshot: AntigravityAgyLogin,
        var process: Process? = null,
        var job: Job? = null,
    )

    private val monitor = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, Session>()
    private val accounts = mutableListOf<AntigravityAgyAccount>()
    private var closed = false
    private var loaded = false

    fun list(): List<AntigravityAgyAccount> = synchronized(monitor) {
        load()
        accounts.toList()
    }

    fun start(owner: String, name: String?, sourceAccountId: String?, accountId: String? = null): AntigravityAgyLogin = synchronized(monitor) {
        if (closed) throw conflict("agy_login_unavailable", "官方 CLI 登录服务正在停止")
        load()
        sessions.entries.removeIf { terminal(it.value.snapshot.state) && it.value.snapshot.expiresAt < System.currentTimeMillis() }
        if (sessions.values.any { !terminal(it.snapshot.state) || it.process != null } || sessions.size >= 32) {
            throw conflict("agy_login_busy", "已有官方 CLI 登录正在进行，请完成或取消后重试")
        }
        val profile = if (accountId != null) accounts.find { it.id == accountId }
            ?: throw notFound("agy_account_not_found", "CLI 授权账号不存在")
        else sourceAccountId?.let { source -> accounts.find { it.sourceAccountId == source } }
        if (profile != null && sourceAccountId != null && profile.sourceAccountId != sourceAccountId) {
            throw badRequest("agy_invalid_source", "重新登录不能更改关联的旧账号")
        }
        val normalized = name?.trim()?.takeIf(String::isNotEmpty) ?: profile?.name ?: "Antigravity CLI"
        if (normalized.length > 100 || normalized.any(Char::isISOControl)) {
            throw badRequest("agy_invalid_name", "账号名称须为 1–100 个可见字符")
        }
        if (profile == null && accounts.size >= 100) throw conflict("agy_account_limit", "已达到 CLI 授权档案数量上限")
        val id = UUID.randomUUID().toString()
        val session = Session(owner, profile?.id ?: UUID.randomUUID().toString(), normalized, sourceAccountId ?: profile?.sourceAccountId,
            AntigravityAgyLogin(id, "PREPARING", System.currentTimeMillis() + 600_000))
        sessions[id] = session
        session.job = scope.launch { run(session) }
        scope.launch {
            delay(600_000)
            runCatching { stop(id, owner, "EXPIRED") }
        }
        session.snapshot
    }

    fun get(loginId: String, owner: String): AntigravityAgyLogin = synchronized(monitor) {
        owned(loginId, owner).snapshot
    }

    fun submit(loginId: String, owner: String, code: String): AntigravityAgyLogin = synchronized(monitor) {
        val session = owned(loginId, owner)
        if (session.snapshot.state != "AWAITING_CODE" || session.snapshot.expiresAt <= System.currentTimeMillis()) {
            throw conflict("agy_login_not_waiting", "登录不在等待验证码状态，请查看最新结果")
        }
        if (!Regex("[A-Za-z0-9._~+/%=-]{1,4096}").matches(code)) {
            throw badRequest("agy_invalid_code", "请仅粘贴官方页面显示的验证码，不要填写 token 或完整回调地址")
        }
        try {
            val message = Json.encodeToString(mapOf("code" to code)) + "\n"
            requireNotNull(session.process).outputStream.apply { write(message.toByteArray(Charsets.UTF_8)); flush() }
        } catch (_: Exception) {
            session.snapshot = session.snapshot.copy(state = "FAILED", authorizeUrl = null, error = "OFFICIAL_CLI_LOGIN_FAILED")
            session.job?.cancel()
            session.process?.destroy()
            throw conflict("agy_login_closed", "官方 CLI 登录进程已退出，请重新发起登录")
        }
        session.snapshot = session.snapshot.copy(state = "VERIFYING", authorizeUrl = null)
        session.snapshot
    }

    fun stop(loginId: String, owner: String, state: String = "CANCELLED") {
        val process = synchronized(monitor) {
            val session = owned(loginId, owner)
            if (session.snapshot.state == "REGISTERING") {
                throw conflict("agy_login_committing", "官方 CLI 登录已完成，账号正在登记，不能取消")
            }
            if (terminal(session.snapshot.state)) return
            session.snapshot = session.snapshot.copy(state = state, authorizeUrl = null)
            session.job?.cancel()
            session.process
        }
        process?.let(::terminate)
    }

    private suspend fun run(session: Session) {
        var process: Process? = null
        try {
            val directory = config.stateDir.resolve(session.profileId)
            process = ProcessBuilder(config.loginCommand(directory))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment().clear() }
                .start()
            synchronized(monitor) {
                if (terminal(session.snapshot.state)) return
                session.process = process
            }
            var loggedIn = false
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = StringBuilder()
                    while (true) {
                        val character = reader.read()
                        if (character == -1 || character == 10) break
                        check(line.length < 16_384)
                        line.append(character.toChar())
                    }
                    if (line.isEmpty()) break
                    val event = Json.parseToJsonElement(line.toString()) as? JsonObject ?: error("invalid event")
                    when ((event["event"] as? JsonPrimitive)?.content) {
                        "preparing" -> Unit
                        "authorize" -> synchronized(monitor) {
                            val url = (event["authorizeUrl"] as? JsonPrimitive)?.content ?: error("missing authorization")
                            check(validAgyAuthorizationUrl(url))
                            if (!terminal(session.snapshot.state)) session.snapshot = session.snapshot.copy(
                                state = "AWAITING_CODE", authorizeUrl = url,
                            )
                        }
                        "verifying" -> Unit
                        "logged_in" -> loggedIn = true
                        else -> error("official CLI failed")
                    }
                }
            }
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 && loggedIn)
            synchronized(monitor) {
                if (terminal(session.snapshot.state)) return
                session.snapshot = session.snapshot.copy(state = "VERIFYING", authorizeUrl = null)
            }
            val catalog = inspect(directory)
            val account = AntigravityAgyAccount(session.profileId, session.name, session.sourceAccountId,
                catalog.officialSubscriptionTier, catalog.currentModelId, System.currentTimeMillis())
            synchronized(monitor) {
                if (terminal(session.snapshot.state)) return
                val next = accounts.filterNot { it.id == account.id } + account
                save(next)
                accounts.clear()
                accounts.addAll(next)
                session.snapshot = session.snapshot.copy(state = "REGISTERING", authorizeUrl = null, account = account)
            }
            onAuthenticated(account)
            synchronized(monitor) {
                if (session.snapshot.state != "REGISTERING") return
                session.snapshot = session.snapshot.copy(state = "AUTHENTICATED", account = account)
            }
        } catch (_: Exception) {
            synchronized(monitor) {
                if (!terminal(session.snapshot.state)) session.snapshot = session.snapshot.copy(
                    state = "FAILED",
                    authorizeUrl = null,
                    error = if (session.snapshot.state == "REGISTERING") {
                        "AGY_ACCOUNT_REGISTRATION_FAILED"
                    } else {
                        "OFFICIAL_CLI_LOGIN_FAILED"
                    },
                )
            }
        } finally {
            process?.let(::terminate)
            synchronized(monitor) { session.process = null }
        }
    }

    private fun owned(id: String, owner: String): Session = sessions[id]?.takeIf { it.owner == owner }
        ?: throw notFound("agy_login_not_found", "登录会话不存在或已过期")

    private fun load() {
        if (loaded) return
        val directory = config.stateDir
        check(generateSequence(directory) { it.parent }.none(Files::isSymbolicLink))
        if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectories(directory,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        check(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
        check(Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
        check(Files.getOwner(directory, NOFOLLOW_LINKS).name == System.getProperty("user.name"))
        val path = directory.resolve("accounts.json")
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 262_144)
            check(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------"))
            check(Files.getOwner(path, NOFOLLOW_LINKS).name == System.getProperty("user.name"))
            val stored = Json.decodeFromString<List<AntigravityAgyAccount>>(Files.readString(path))
            check(stored.size <= 100 && stored.map { it.id }.distinct().size == stored.size)
            check(stored.all { UUID.fromString(it.id).toString() == it.id && !it.routingEnabled })
            accounts.addAll(stored)
        }
        loaded = true
    }

    private fun save(value: List<AntigravityAgyAccount>) {
        val temporary = Files.createTempFile(config.stateDir, ".accounts-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temporary, Json.encodeToString(value))
            Files.move(temporary, config.stateDir.resolve("accounts.json"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun terminate(process: Process) {
        val children = process.descendants().toList()
        process.destroy()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            children.asReversed().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        val active = synchronized(monitor) {
            closed = true
            sessions.values.filter { !terminal(it.snapshot.state) }.onEach {
                it.snapshot = it.snapshot.copy(state = "CANCELLED", authorizeUrl = null)
            }.mapNotNull { it.process }
        }
        active.forEach(::terminate)
        scope.cancel()
    }

    private fun terminal(state: String) = state in setOf("AUTHENTICATED", "CANCELLED", "EXPIRED", "FAILED")

    private fun conflict(code: String, message: String) =
        GatewayError(HttpStatusCode.Conflict, code, message, "invalid_request_error")
}

internal fun validAgyAuthorizationUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val pairs = uri.rawQuery.orEmpty().split('&').map { field ->
        val parts = field.split('=', limit = 2)
        URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
    }
    val query = pairs.toMap()
    value.length <= 8192 && uri.scheme == "https" && uri.rawAuthority == "accounts.google.com" &&
        uri.path in setOf("/o/oauth2/v2/auth", "/o/oauth2/auth") && uri.fragment == null &&
        pairs.size == query.size && query["response_type"] == "code" &&
        query["client_id"].orEmpty().endsWith(".apps.googleusercontent.com") &&
        query["redirect_uri"] == "https://antigravity.google/oauth-callback"
}.getOrDefault(false)
