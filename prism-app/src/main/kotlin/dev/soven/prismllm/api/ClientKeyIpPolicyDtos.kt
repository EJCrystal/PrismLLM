package dev.soven.prismllm.api

import dev.soven.prismllm.quota.domain.ClientKeyIpPolicy
import dev.soven.prismllm.shared.error.badRequest
import kotlinx.serialization.Serializable

@Serializable
data class ClientKeyIpPolicyDto(
    val allowlist: List<String> = emptyList(),
    val denylist: List<String> = emptyList(),
)

fun ClientKeyIpPolicy.toDto(): ClientKeyIpPolicyDto = ClientKeyIpPolicyDto(
    allowlist = allowlist.sorted(),
    denylist = denylist.sorted(),
)

internal fun ClientKeyIpPolicyDto.toPolicy(): ClientKeyIpPolicy = try {
    require(allowlist.size <= 256 && denylist.size <= 256) { "每份 IP 名单最多允许 256 条规则" }
    ClientKeyIpPolicy(allowlist.toSet(), denylist.toSet()).normalized()
} catch (error: IllegalArgumentException) {
    throw badRequest("invalid_client_key_ip_policy", error.message ?: "无效的 IP 访问规则")
}
