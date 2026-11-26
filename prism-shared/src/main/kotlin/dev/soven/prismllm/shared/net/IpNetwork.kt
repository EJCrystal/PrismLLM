package dev.soven.prismllm.shared.net

import java.net.InetAddress

class IpNetwork private constructor(
    private val networkAddress: ByteArray,
    private val prefixLength: Int,
) {
    fun contains(address: String?): Boolean {
        val candidate = address?.let(::parseAddress) ?: return false
        if (candidate.size != networkAddress.size) return false
        val wholeBytes = prefixLength / 8
        for (index in 0 until wholeBytes) {
            if (candidate[index] != networkAddress[index]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)).toByte().toInt()
        return (candidate[wholeBytes].toInt() and mask) == (networkAddress[wholeBytes].toInt() and mask)
    }

    companion object {
        fun isIpAddress(value: String?): Boolean = value != null && parseAddress(value) != null

        fun parse(value: String): IpNetwork {
            val parts = value.trim().split('/', limit = 2)
            val address = parseAddress(parts[0])
            require(address != null) { "无效的 IP 地址或 CIDR 网段: $value" }
            val mappedIpv4 = parts[0].contains(':') && address.size == 4
            val maxPrefixLength = if (mappedIpv4) 128 else address.size * 8
            val prefix = parts.getOrNull(1)
            require(prefix == null || prefix.isNotEmpty() && prefix.all { it in '0'..'9' }) {
                "无效的 CIDR 前缀: $value"
            }
            val rawPrefixLength = if (prefix == null) maxPrefixLength else prefix.toIntOrNull()
            require(rawPrefixLength != null && rawPrefixLength in 0..maxPrefixLength) {
                "CIDR 前缀超出范围: $value"
            }
            require(!mappedIpv4 || rawPrefixLength >= 96) { "IPv4 映射地址的 CIDR 前缀必须至少为 96: $value" }
            return IpNetwork(address, if (mappedIpv4) rawPrefixLength - 96 else rawPrefixLength)
        }

        private fun parseAddress(value: String): ByteArray? {
            val address = value.trim()
            if (address.isEmpty() || address.length > 45) return null
            return if (address.contains(':')) {
                if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return null
                runCatching { InetAddress.getByName(address).address }.getOrNull()
                    ?.takeIf { it.size == 16 || it.size == 4 }
            } else {
                val octets = address.split('.')
                if (octets.size != 4) return null
                octets.mapNotNull { octet ->
                    octet.takeIf { it.isNotEmpty() && it.length <= 3 && it.all { digit -> digit in '0'..'9' } }
                        ?.toIntOrNull()
                        ?.takeIf { it in 0..255 }
                        ?.toByte()
                }.takeIf { it.size == 4 }?.toByteArray()
            }
        }
    }
}
