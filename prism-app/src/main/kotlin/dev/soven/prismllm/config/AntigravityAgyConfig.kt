package dev.soven.prismllm.config

import java.nio.file.Path

data class AntigravityAgyConfig(
    val script: Path,
    val stateDir: Path,
    val python: Path = Path.of("/usr/bin/python3"),
    val proxy: String = "http://127.0.0.1:7897",
) {
    init {
        require(listOf(script, stateDir, python).all { it.isAbsolute && '\n' !in it.toString() && '\r' !in it.toString() })
        val port = Regex("http://127\\.0\\.0\\.1:([0-9]+)").matchEntire(proxy)?.groupValues?.get(1)?.toIntOrNull()
        require(port != null && port in 1..65535) { "Antigravity agy requires a credential-free loopback HTTP proxy" }
    }

    fun loginCommand(state: Path): List<String> = listOf(
        python.toString(), script.toString(), "--state", state.toString(), "--proxy", proxy,
    )

    fun inspectCommand(state: Path): List<String> = listOf(
        python.toString(), script.resolveSibling("antigravity_acp_pilot.py").toString(),
        "--state", state.toString(), "--proxy", proxy, "agent", "--online", "--", "acp",
    )

    fun gatewayCommand(state: Path, modelId: String): List<String> = listOf(
        python.toString(), script.resolveSibling("antigravity_acp_pilot.py").toString(),
        "--state", state.toString(), "--proxy", proxy, "gateway-agent", "--model", modelId,
    )

    companion object {
        fun fromEnv(env: Map<String, String>): AntigravityAgyConfig? {
            when (env["ANTIGRAVITY_AGY_ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("ANTIGRAVITY_AGY_ENABLED must be true or false")
            }
            fun required(name: String) = requireNotNull(env["ANTIGRAVITY_AGY_$name"]) {
                "ANTIGRAVITY_AGY_$name is required"
            }
            return AntigravityAgyConfig(
                script = Path.of(required("SCRIPT")),
                stateDir = Path.of(required("STATE_DIR")),
                python = Path.of(env["ANTIGRAVITY_AGY_PYTHON"] ?: "/usr/bin/python3"),
                proxy = env["ANTIGRAVITY_AGY_PROXY"] ?: "http://127.0.0.1:7897",
            )
        }
    }
}
