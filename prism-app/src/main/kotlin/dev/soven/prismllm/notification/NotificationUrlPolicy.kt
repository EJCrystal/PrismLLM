package dev.soven.prismllm.notification

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

internal fun interface NotificationDnsResolver {
    fun resolve(host: String): List<InetAddress>
}

/** No URL or DNS exception includes the endpoint, which may itself contain a credential. */
internal class NotificationUrlRejected : Exception("unsafe_url")

internal object NotificationUrlPolicy {
    fun parse(value: String): HttpUrl {
        if (value.length > 8_192 || value.any { it <= ' ' || it == '\\' || it.code == 127 }) reject()
        val uri = try { URI(value) } catch (_: Exception) { reject() }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawFragment != null || uri.rawUserInfo != null) reject()
        val authority = uri.rawAuthority ?: reject()
        if ('@' in authority || '%' in authority || uri.host.isNullOrEmpty()) reject()
        val rawHost = uri.host.removeSurrounding("[", "]").lowercase()
        if (rawHost.endsWith('.') || rawHost == "localhost" || rawHost.endsWith(".localhost") ||
            rawHost == "local" || rawHost.endsWith(".local")) reject()
        val url = value.toHttpUrlOrNull() ?: reject()
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.port !in 1..65535) reject()
        if (':' in rawHost) {
            // Embedded IPv4 and zone identifiers are intentionally not accepted, even when public.
            if ('.' in rawHost || '%' in rawHost || !rawHost.all { it in "0123456789abcdef:" }) reject()
            val literal = try { InetAddress.getByName(rawHost) } catch (_: Exception) { reject() }
            if (literal !is Inet6Address || !isPublicAddress(literal)) reject()
        } else {
            val parts = rawHost.split('.')
            val numeric = parts.all { it.all(Char::isDigit) || it.startsWith("0x", ignoreCase = true) }
            if (numeric) {
                val bytes = canonicalIpv4(rawHost) ?: reject()
                if (!isPublicAddress(InetAddress.getByAddress(bytes))) reject()
            } else if (parts.size < 2 || parts.any { part ->
                    part.isEmpty() || part.length > 63 || part.first() == '-' || part.last() == '-' ||
                        part.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
                } || rawHost.length > 253 || parts.last().all(Char::isDigit)) reject()
        }
        // OkHttp must connect to exactly the host whose spelling was checked above.
        if (':' !in rawHost && url.host != rawHost) reject()
        return url
    }

    suspend fun resolve(url: HttpUrl, resolver: NotificationDnsResolver): NotificationPinnedDns {
        val addresses = resolver.resolve(url.host)
        if (addresses.isEmpty()) throw UnknownHostException("dns_failure")
        if (addresses.any { !isPublicAddress(it) }) throw NotificationUrlRejected()
        // Reconstruct from bytes to discard resolver-owned names, scopes and mutable collections.
        val snapshot = addresses.map { InetAddress.getByAddress(it.address.clone()) }.distinctBy { it.hostAddress }
        return NotificationPinnedDns(url.host, snapshot)
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 4) {
            val ip = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
            return IPV4_DENY.none { (base, bits) -> ip ushr (32 - bits) == base ushr (32 - bits) }
        }
        if (bytes.size != 16 || (address is Inet6Address &&
                (address.scopeId != 0 || address.scopedInterface != null))) return false
        val first = bytes[0].toInt() and 255
        // Only global unicast 2000::/3; this also denies mapped, NAT64, ULA, link-local and multicast.
        if (first and 0xe0 != 0x20) return false
        return IPV6_DENY.none { (prefix, bits) -> matchesPrefix(bytes, prefix, bits) }
    }

    private fun canonicalIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val result = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || (part.length > 1 && part[0] == '0') || part.any { it !in '0'..'9' }) return null
            val number = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            result[index] = number.toByte()
        }
        return result
    }

    private fun matchesPrefix(value: ByteArray, prefix: ByteArray, bits: Int): Boolean {
        val whole = bits / 8
        if ((0 until whole).any { value[it] != prefix[it] }) return false
        val remainder = bits % 8
        return remainder == 0 || (value[whole].toInt() and (0xff shl (8 - remainder))) ==
            (prefix[whole].toInt() and (0xff shl (8 - remainder)))
    }

    private fun reject(): Nothing = throw NotificationUrlRejected()

    // Conservative exclusions from the IPv4 special-purpose registry (including nested exceptions).
    private val IPV4_DENY = listOf(
        0x00000000L to 8, 0x0a000000L to 8, 0x64400000L to 10,
        0x7f000000L to 8, 0xa9fe0000L to 16, 0xac100000L to 12,
        0xc0000000L to 24, 0xc0000200L to 24, 0xc01fc400L to 24,
        0xc034c100L to 24, 0xc0586300L to 24, 0xc0a80000L to 16,
        0xc6120000L to 15, 0xc6336400L to 24, 0xc0af3000L to 24,
        0xcb007100L to 24, 0xe0000000L to 4, 0xf0000000L to 4,
    )

    // IETF assignments, Teredo, benchmarking, ORCHID, documentation and 6to4 must not tunnel SSRF.
    private val IPV6_DENY = listOf(
        byteArrayOf(0x20, 0x01, 0x00, 0x00) to 23,
        byteArrayOf(0x20, 0x01, 0x00, 0x20) to 28,
        byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()) to 32,
        byteArrayOf(0x20, 0x02) to 16,
        byteArrayOf(0x26, 0x20, 0x00, 0x4f, 0x80.toByte(), 0x00) to 48,
        byteArrayOf(0x3f, 0xff.toByte(), 0x00) to 20,
    )
}

/** One immutable DNS snapshot for one client; never calls the system resolver during connection. */
internal class NotificationPinnedDns(
    private val host: String,
    addresses: List<InetAddress>,
) : Dns {
    private val addresses = addresses.toList()

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(host, ignoreCase = true)) throw UnknownHostException("unsafe_url")
        return addresses.toList()
    }
}
