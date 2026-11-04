package dev.soven.llmgateway.config

import java.net.InetAddress

class TrustedProxyNetwork private constructor(
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

        fun parse(value: String): TrustedProxyNetwork {
            val parts = value.trim().split('/', limit = 2)
            require(parts.size in 1..2 && parts[0].isNotBlank()) { "无效的可信代理网段: $value" }
            val address = parseAddress(parts[0])
                ?: error("无效的可信代理 IP 地址: $value")
            val mappedIpv4 = parts[0].contains(':') && address.size == 4
            val maxPrefixLength = if (mappedIpv4) 128 else address.size * 8
            val rawPrefixLength = parts.getOrNull(1)?.toIntOrNull() ?: if (parts.size == 1) {
                maxPrefixLength
            } else {
                error("无效的可信代理网段前缀: $value")
            }
            require(rawPrefixLength in 0..maxPrefixLength) { "可信代理网段前缀超出范围: $value" }
            if (mappedIpv4) {
                require(rawPrefixLength >= 96) { "可信代理网段前缀超出范围: $value" }
            }
            val prefixLength = if (mappedIpv4) rawPrefixLength - 96 else rawPrefixLength
            return TrustedProxyNetwork(address, prefixLength)
        }

        fun parseList(value: String): List<TrustedProxyNetwork> =
            value.split(',').map(String::trim).filter(String::isNotEmpty).map(::parse)

        private fun parseAddress(value: String): ByteArray? {
            val address = value.trim()
            if (address.isEmpty()) return null
            val bytes = if (address.contains(':')) {
                if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return null
                runCatching { InetAddress.getByName(address).address }.getOrNull()
                    ?.takeIf { it.size == 16 || it.size == 4 }
            } else {
                val octets = address.split('.')
                if (octets.size != 4) return null
                octets.mapNotNull { octet ->
                    octet.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                        ?.toIntOrNull()
                        ?.takeIf { it in 0..255 }
                        ?.toByte()
                }.takeIf { it.size == 4 }?.toByteArray()
            } ?: return null

            return if (
                bytes.size == 16 &&
                bytes.sliceArray(0..9).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            ) {
                bytes.copyOfRange(12, 16)
            } else {
                bytes
            }
        }
    }
}
