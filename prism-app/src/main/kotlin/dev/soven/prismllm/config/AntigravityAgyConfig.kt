package dev.soven.prismllm.config

import java.net.URI
import java.nio.file.Path

data class AntigravityAgyConfig(
    val script: Path,
    val stateDir: Path,
    val python: Path = Path.of("/usr/bin/python3"),
    val proxy: String = "",
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
) {
    init {
        require(listOf(script, stateDir, python).all { it.isAbsolute && '\n' !in it.toString() && '\r' !in it.toString() })
        if (proxy.isNotEmpty()) {
            val uri = runCatching { URI(proxy) }.getOrNull()
            require(uri != null && uri.scheme == "http" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawPath in listOf("", "/") && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)
            ) { "Antigravity agy proxy must be an HTTP endpoint without embedded credentials or a path" }
        }
        require((proxyUsername == null) == (proxyPassword == null)) { "Both proxy username and password are required" }
        proxyUsername?.let { username ->
            require(proxy.isNotEmpty() && username.isNotBlank() && username.toByteArray(Charsets.UTF_8).size <= 255 &&
                username.none { it.isISOControl() } && ':' !in username) { "Invalid Antigravity agy proxy username" }
        }
        proxyPassword?.let { password ->
            require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= 255 &&
                password.none { it.isISOControl() }) { "Invalid Antigravity agy proxy password" }
        }
    }

    fun proxyEnvironment(): Map<String, String> = if (proxyUsername == null) emptyMap() else mapOf(
        "AGY_PROXY_USERNAME" to proxyUsername,
        "AGY_PROXY_PASSWORD" to requireNotNull(proxyPassword),
    )

    override fun toString(): String =
        "AntigravityAgyConfig(script=$script, stateDir=$stateDir, python=$python, proxy=$proxy, proxyUsername=$proxyUsername, proxyPassword=[REDACTED])"

    fun loginCommand(state: Path): List<String> = listOf(
        python.toString(), script.toString(), "--state", state.toString(), "--proxy", proxy,
    )

    fun preparationCommand(): List<String> = loginCommand(stateDir.resolve(".runtime")) + "--prepare"

    fun inspectCommand(state: Path): List<String> = listOf(
        python.toString(), script.resolveSibling("antigravity_acp_pilot.py").toString(),
        "--state", state.toString(), "--proxy", proxy, "agent", "--online", "--", "acp",
    )

    fun gatewayCommand(state: Path, modelId: String): List<String> = listOf(
        python.toString(), script.resolveSibling("antigravity_acp_pilot.py").toString(),
        "--state", state.toString(), "--proxy", proxy, "gateway-agent", "--model", modelId,
    )

    companion object {
        fun defaults(): AntigravityAgyConfig = AntigravityAgyConfig(
            script = Path.of("scripts/antigravity_agy_admin.py").toAbsolutePath().normalize(),
            stateDir = Path.of("data/antigravity-agy").toAbsolutePath().normalize(),
        )
    }
}
