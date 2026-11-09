package dev.soven.llmgateway.requestlog.domain

import dev.soven.llmgateway.pricing.domain.ModelPriceTier
import dev.soven.llmgateway.pricing.domain.PricingMode
import dev.soven.llmgateway.shared.domain.FailureStage
import dev.soven.llmgateway.shared.domain.GatewayProtocol
import java.math.BigDecimal

/**
 * 请求日志记录。默认不含 prompt 与响应正文。
 *
 * @author 文杰
 * @since 2026-10-21
 */
data class RequestLogRecord(
    val requestId: String,
    val traceId: String? = null,
    val clientKeyId: String?,
    val clientKeyName: String?,
    /** 调用方 IP；取不到时为 null。 */
    val clientIp: String? = null,
    /** 调用方 User-Agent，截断保存；缺失时为 null。 */
    val clientUserAgent: String? = null,
    val alias: String,
    val channelId: String?,
    val channelName: String?,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val upstreamModel: String?,
    val protocol: GatewayProtocol,
    val upstreamProtocol: GatewayProtocol? = null,
    val stream: Boolean? = null,
    val statusCode: Int,
    val firstTokenMs: Long?,
    val totalMs: Long,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cachedTokens: Long?,
    val cachedWriteTokens: Long? = null,
    val reasoningTokens: Long?,
    val reasoningEffort: String?,
    val totalTokens: Long?,
    val usageKnown: Boolean,
    val costAmount: BigDecimal?,
    val costCurrency: String?,
    val inputPricePerMillion: BigDecimal?,
    val outputPricePerMillion: BigDecimal?,
    val cachedInputPricePerMillion: BigDecimal?,
    val cachedInputWritePricePerMillion: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.TOKEN,
    val perRequestPrice: BigDecimal? = null,
    val priceTiers: List<ModelPriceTier> = emptyList(),
    val errorCode: String?,
    val failureStage: FailureStage?,
    val errorMessage: String?,
    val upstreamErrorCode: String?,
    val upstreamErrorBody: String? = null,
    val createdAt: Long,
    val upstreamAttempts: Int? = null,
    /** 每次上游尝试或路由跳过的明细，按发生顺序排列。 */
    val attempts: List<UpstreamAttemptRecord> = emptyList(),
    val pricingModelId: String? = null,
    val id: Long = 0,
)

/**
 * 单次上游尝试或路由跳过的日志明细。只记录路由与结果元数据，不保存 prompt 或响应正文。
 *
 * @author 文杰
 * @since 2026-10-30
 */
data class UpstreamAttemptRecord(
    /** 实际上游尝试序号；路由跳过记录为 0。 */
    val index: Int,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelKeyId: String? = null,
    val channelKeyName: String? = null,
    val upstreamModel: String? = null,
    /** 上游响应自报的模型标识；历史记录或上游未提供时为 null。 */
    val upstreamReportedModel: String? = null,
    val upstreamProtocol: GatewayProtocol? = null,
    /** 未拿到上游 HTTP 响应时为 null。 */
    val statusCode: Int? = null,
    val upstreamErrorStatusCode: Int? = null,
    /** 本次尝试的网关错误码；成功为 null。 */
    val errorCode: String? = null,
    /** 上游返回的错误码；成功或非上游错误为 null。 */
    val upstreamErrorCode: String? = null,
    val errorMessage: String? = null,
    val elapsedMs: Long,
    val firstTokenMs: Long? = null,
    /** 本次尝试已向客户端提交响应。 */
    val committed: Boolean = false,
    val retryable: Boolean = false,
    /** 该条记录是路由阶段被跳过的候选，没有发起上游请求。 */
    val skipped: Boolean = false,
    /** 跳过原因：cooldown 或 oauth_window / oauth_concurrency / oauth_cooldown。skipped=false 时为 null。 */
    val skipReason: String? = null,
)
