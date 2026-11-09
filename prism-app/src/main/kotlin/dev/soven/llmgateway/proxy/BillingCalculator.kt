package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.domain.RequestLogRecord
import dev.soven.llmgateway.pricing.domain.BillingRequest
import dev.soven.llmgateway.pricing.domain.CalculatedCost
import dev.soven.llmgateway.pricing.domain.DefaultModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceRecord
import dev.soven.llmgateway.pricing.domain.ModelPriceSource
import dev.soven.llmgateway.pricing.domain.PricingCalculator
import dev.soven.llmgateway.pricing.domain.ResolvedModelPrice

/**
 * 计费入口：把请求日志与价格记录适配成计价模块的输入，具体口径由 [PricingCalculator] 实现。
 */
object BillingCalculator {
    fun calculate(
        record: RequestLogRecord,
        price: ModelPriceRecord,
        requestSucceeded: Boolean = record.statusCode in 200..299 && record.errorCode == null && record.failureStage == null,
    ): CalculatedCost? = calculate(record, price.toResolved(), requestSucceeded)

    fun calculate(
        record: RequestLogRecord,
        price: DefaultModelPriceRecord,
        requestSucceeded: Boolean = record.statusCode in 200..299 && record.errorCode == null && record.failureStage == null,
    ): CalculatedCost? = calculate(record, price.toResolved(), requestSucceeded)

    fun calculate(
        record: RequestLogRecord,
        price: ResolvedModelPrice,
        requestSucceeded: Boolean = record.statusCode in 200..299 && record.errorCode == null && record.failureStage == null,
    ): CalculatedCost? = PricingCalculator.calculate(record.toBillingRequest(requestSucceeded), price)

    private fun RequestLogRecord.toBillingRequest(succeeded: Boolean) = BillingRequest(
        usageKnown = usageKnown,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        cachedWriteTokens = cachedWriteTokens,
        succeeded = succeeded,
    )

    private fun ModelPriceRecord.toResolved() = ResolvedModelPrice(
        source = ModelPriceSource.CHANNEL_OVERRIDE,
        modelId = upstreamModel,
        channelPriceId = id,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        cachedInputPricePerMillion = cachedInputPricePerMillion,
        cachedInputWritePricePerMillion = cachedInputWritePricePerMillion,
        pricingMode = pricingMode,
        perRequestPrice = perRequestPrice,
        priceTiers = priceTiers,
    )

    private fun DefaultModelPriceRecord.toResolved() = ResolvedModelPrice(
        source = ModelPriceSource.MODEL_DEFAULT,
        modelId = modelId,
        defaultPriceId = id,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        cachedInputPricePerMillion = cachedInputPricePerMillion,
        cachedInputWritePricePerMillion = cachedInputWritePricePerMillion,
        pricingMode = pricingMode,
        perRequestPrice = perRequestPrice,
        priceTiers = priceTiers,
    )
}