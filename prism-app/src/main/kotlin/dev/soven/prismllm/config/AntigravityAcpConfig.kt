package dev.soven.prismllm.config

import java.nio.file.Path
import java.util.Collections

class AntigravityAcpConfig(
    val script: Path,
    val stateDir: Path,
    val python: Path = DEFAULT_PYTHON,
    val proxy: String = DEFAULT_PROXY,
    val modelId: String,
    clientKeyIds: Set<String>,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    val clientKeyIds: Set<String> = Collections.unmodifiableSet(clientKeyIds.toSet())

    init {
        validatePath("SCRIPT", script)
        validatePath("STATE_DIR", stateDir)
        validatePath("PYTHON", python)
        require(isValidProxy(proxy)) {
            "${ENV_PREFIX}PROXY must be credential-free HTTP on 127.0.0.1 with no path, query or fragment" +
                " and an explicit port between 1 and 65535"
        }
        require(MODEL_PATTERN.matches(modelId)) {
            "${ENV_PREFIX}MODEL must match [A-Za-z0-9._:/-]{1,160}"
        }
        require(this.clientKeyIds.isNotEmpty() && this.clientKeyIds.all(UUID_PATTERN::matches)) {
            "${ENV_PREFIX}CLIENT_KEY_IDS must contain nonempty UUID strings"
        }
        require(timeoutMs in 1_000L..600_000L) {
            "${ENV_PREFIX}TIMEOUT_MS must be between 1000 and 600000"
        }
    }

    fun command(): List<String> = listOf(
        python.toString(),
        script.toString(),
        "--state",
        stateDir.toString(),
        "--proxy",
        proxy,
        "gateway-agent",
        "--model",
        modelId,
    )

    companion object {
        private const val ENV_PREFIX = "ANTIGRAVITY_ACP_"
        private val DEFAULT_PYTHON = Path.of("/usr/bin/python3")
        private const val DEFAULT_PROXY = "http://127.0.0.1:7897"
        private const val DEFAULT_TIMEOUT_MS = 120_000L
        private val MODEL_PATTERN = Regex("[A-Za-z0-9._:/-]{1,160}")
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private val PROXY_PATTERN = Regex("http://127\\.0\\.0\\.1:([0-9]+)", RegexOption.IGNORE_CASE)
        private val LINE_BREAK_PATTERN = Regex("\\R")

        fun fromEnv(env: Map<String, String>): AntigravityAcpConfig? {
            when (env["${ENV_PREFIX}ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> throw IllegalArgumentException("${ENV_PREFIX}ENABLED must be true or false")
            }

            fun required(name: String): String = requireNotNull(env["$ENV_PREFIX$name"]) {
                "$ENV_PREFIX$name is required when ${ENV_PREFIX}ENABLED is true"
            }

            val rawClientKeyIds = required("CLIENT_KEY_IDS")
            require(!LINE_BREAK_PATTERN.containsMatchIn(rawClientKeyIds)) {
                "${ENV_PREFIX}CLIENT_KEY_IDS must not contain line breaks"
            }
            val timeoutMs = env["${ENV_PREFIX}TIMEOUT_MS"]?.let { raw ->
                requireNotNull(raw.toLongOrNull()) {
                    "${ENV_PREFIX}TIMEOUT_MS must be an integer between 1000 and 600000"
                }
            } ?: DEFAULT_TIMEOUT_MS

            return AntigravityAcpConfig(
                script = Path.of(required("SCRIPT")),
                stateDir = Path.of(required("STATE_DIR")),
                python = env["${ENV_PREFIX}PYTHON"]?.let { Path.of(it) } ?: DEFAULT_PYTHON,
                proxy = env["${ENV_PREFIX}PROXY"] ?: DEFAULT_PROXY,
                modelId = required("MODEL"),
                clientKeyIds = rawClientKeyIds.split(',').map { it.trim() }.toSet(),
                timeoutMs = timeoutMs,
            )
        }

        private fun validatePath(name: String, path: Path) {
            require(path.isAbsolute && !LINE_BREAK_PATTERN.containsMatchIn(path.toString())) {
                "$ENV_PREFIX$name must be an absolute path without line breaks"
            }
        }

        private fun isValidProxy(proxy: String): Boolean {
            val match = PROXY_PATTERN.matchEntire(proxy) ?: return false
            val port = match.groups[1]?.value ?: return false
            return port.toIntOrNull()?.let { it in 1..65_535 } == true
        }
    }
}
