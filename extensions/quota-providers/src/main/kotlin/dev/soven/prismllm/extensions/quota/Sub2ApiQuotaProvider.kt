package dev.soven.prismllm.extensions.quota

import dev.soven.prismllm.spi.quota.*
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.net.http.HttpClient

class Sub2ApiQuotaProvider : HttpQuotaProvider() {
    override val id = "sub2api"
    override val displayName = "Sub2API"

    override fun queryRemote(request: ChannelQuotaQuery, config: JsonObject, client: HttpClient): ChannelQuotaResult {
        val body = get(client, siteBase(request, config) + "/v1/usage", request.credential)
        if (body.bool("isValid") == false) return ChannelQuotaResult(
            ChannelQuotaStatus.AUTHENTICATION_FAILED, message = "上游凭据无效或已停用",
        )
        val balance = body.number("balance")
        if (balance != null) return ChannelQuotaResult(
            ChannelQuotaStatus.AVAILABLE, remaining = balance,
            unit = body.string("unit"), planName = body.string("planName"),
        )
        if (body.string("mode") == "quota_limited" || body.obj("quota") != null) {
            return ChannelQuotaResult(ChannelQuotaStatus.UNSUPPORTED,
                message = "上游仅返回此密钥的限额，无法确认渠道共享余额；请使用没有额度及速率限制的上游密钥")
        }
        if (body.obj("subscription") != null) {
            val remaining = body.number("remaining")
                ?: return ChannelQuotaResult(ChannelQuotaStatus.UNSUPPORTED, message = "上游没有返回订阅共享剩余额度")
            return if (remaining.compareTo(BigDecimal("-1")) == 0) ChannelQuotaResult(
                ChannelQuotaStatus.UNLIMITED, unit = body.string("unit"), planName = body.string("planName"),
            ) else ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE, remaining = remaining,
                unit = body.string("unit"), planName = body.string("planName"))
        }
        return ChannelQuotaResult(ChannelQuotaStatus.UNSUPPORTED, message = "响应中没有可确认的渠道共享余额")
    }
}
