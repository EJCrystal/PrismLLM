package dev.soven.prismllm.config

import dev.soven.prismllm.shared.net.IpNetwork

class TrustedProxyNetwork private constructor(private val network: IpNetwork) {
    fun contains(address: String?): Boolean = network.contains(address)

    companion object {
        fun isIpAddress(value: String?): Boolean = IpNetwork.isIpAddress(value)

        fun parse(value: String): TrustedProxyNetwork = TrustedProxyNetwork(IpNetwork.parse(value))

        fun parseList(value: String): List<TrustedProxyNetwork> =
            value.split(',').map(String::trim).filter(String::isNotEmpty).map(::parse)
    }
}
