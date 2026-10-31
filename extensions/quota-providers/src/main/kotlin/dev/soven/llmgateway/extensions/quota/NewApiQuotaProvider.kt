package dev.soven.llmgateway.extensions.quota

import dev.soven.llmgateway.spi.quota.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.http.HttpClient

class NewApiQuotaProvider : HttpQuotaProvider() {
    override val id = "new-api"
    override val displayName = "New API"

    override fun queryRemote(request: ChannelQuotaQuery, config: JsonObject, client: HttpClient): ChannelQuotaResult {
        val base = siteBase(request, config)
        val remaining: BigDecimal
        val used: BigDecimal?
        when (config.string("authMode") ?: "token") {
            "system" -> {
                val userId = (config["userId"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }
                    ?: return ChannelQuotaResult(ChannelQuotaStatus.ERROR, message = "请填写有效的 New API 用户 ID（正整数）")
                val body = get(client, base + "/api/user/self", request.credential, mapOf("New-Api-User" to userId.toString()))
                if (body.bool("success") != true) return ChannelQuotaResult(
                    ChannelQuotaStatus.ERROR, message = "上游未返回有效账户信息，请核对系统访问令牌和用户 ID",
                )
                val user = body.obj("data") ?: error("missing account data")
                if (user.number("id")?.compareTo(BigDecimal.valueOf(userId)) != 0) return ChannelQuotaResult(
                    ChannelQuotaStatus.AUTHENTICATION_FAILED, message = "系统访问令牌所属用户与配置的用户 ID 不一致",
                )
                remaining = user.number("quota") ?: error("missing account quota")
                used = user.number("used_quota")
            }
            "token" -> {
                val body = get(client, base + "/api/usage/token/", request.credential)
                if (body.bool("code") == false || body.bool("success") == false) return ChannelQuotaResult(
                    ChannelQuotaStatus.ERROR, message = "上游未能返回有效的账户额度",
                )
                val user = body.obj("data")?.obj("user_quota") ?: return ChannelQuotaResult(
                    ChannelQuotaStatus.UNSUPPORTED,
                    message = "此上游未通过模型令牌提供账户额度，请改用系统访问令牌 + 用户 ID 查询",
                )
                remaining = user.number("total_available") ?: error("missing account quota")
                used = user.number("total_used")
            }
            else -> return ChannelQuotaResult(ChannelQuotaStatus.ERROR, message = "New API 额度查询认证方式无效")
        }
        val status = try { get(client, base + "/api/status").obj("data") } catch (cause: Exception) {
            if (cause is InterruptedException) throw cause
            null
        }
        val divisor = status?.number("quota_per_unit")?.takeIf { it > BigDecimal.ZERO }
        val display = status?.string("quota_display_type")
            ?: if (status?.bool("display_in_currency") == true) "USD" else null
        val multiplier = when (display) {
            "USD" -> BigDecimal.ONE
            "CNY" -> status?.number("usd_exchange_rate")?.takeIf { it > BigDecimal.ZERO }
            "CUSTOM" -> status?.number("custom_currency_exchange_rate")?.takeIf { it > BigDecimal.ZERO }
            else -> null
        }
        val unit = when (display) {
            "USD", "CNY" -> display
            "CUSTOM" -> status?.string("custom_currency_symbol")?.takeIf { it.isNotBlank() }
            else -> null
        }
        if (divisor == null || multiplier == null || unit == null) return ChannelQuotaResult(
            ChannelQuotaStatus.AVAILABLE, remaining = remaining, used = used, unit = "quota",
            message = "无法确定上游币种或换算参数，按原始 quota 单位显示",
        )
        fun convert(number: BigDecimal?) = number?.multiply(multiplier)?.divide(divisor, 8, RoundingMode.HALF_UP)
        return ChannelQuotaResult(ChannelQuotaStatus.AVAILABLE, remaining = convert(remaining), used = convert(used), unit = unit)
    }
}
