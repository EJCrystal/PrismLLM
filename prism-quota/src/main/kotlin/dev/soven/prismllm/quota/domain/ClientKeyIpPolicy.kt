package dev.soven.prismllm.quota.domain

import dev.soven.prismllm.shared.net.IpNetwork

data class ClientKeyIpPolicy(
    val allowlist: Set<String> = emptySet(),
    val denylist: Set<String> = emptySet(),
) {
    private val allowedNetworks = parseNetworks(allowlist)
    private val deniedNetworks = parseNetworks(denylist)

    val isUnrestricted: Boolean
        get() = allowlist.isEmpty() && denylist.isEmpty()

    fun allows(address: String?): Boolean {
        if (isUnrestricted) return true
        if (!IpNetwork.isIpAddress(address)) return false
        return deniedNetworks.none { it.contains(address) } &&
            (allowedNetworks.isEmpty() || allowedNetworks.any { it.contains(address) })
    }

    fun normalized(): ClientKeyIpPolicy = copy(
        allowlist = allowlist.map(String::trim).toSet(),
        denylist = denylist.map(String::trim).toSet(),
    )

    private fun parseNetworks(entries: Set<String>): List<IpNetwork> {
        require(entries.size <= 256) { "每份 IP 名单最多允许 256 条规则" }
        return entries.map { entry ->
            require(entry.trim().length in 1..64) { "IP 规则长度必须为 1 至 64 个字符" }
            IpNetwork.parse(entry)
        }
    }
}
