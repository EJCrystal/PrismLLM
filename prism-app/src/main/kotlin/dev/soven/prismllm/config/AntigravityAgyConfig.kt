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
