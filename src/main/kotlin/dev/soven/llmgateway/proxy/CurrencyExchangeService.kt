package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.api.ExchangeRatesResponse
import dev.soven.llmgateway.api.GatewayException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * 管理面币种换算所需的 USD 基准汇率。数据来自 Frankfurter（欧洲央行参考汇率），短时缓存。
 */
class CurrencyExchangeService(
    private val httpClient: HttpClient,
    private val endpoint: String = "https://api.frankfurter.dev/v1/latest?base=USD&symbols=CNY,EUR",
    private val cacheTtlMs: Long = 6 * 60 * 60 * 1000L,
) {
    private val logger = LoggerFactory.getLogger(CurrencyExchangeService::class.java)
    private val loadMutex = Mutex()
    @Volatile private var cached: CachedRates? = null

    suspend fun getRates(): ExchangeRatesResponse {
        val now = System.currentTimeMillis()
        cached?.takeIf { it.expiresAt > now }?.let { return it.value }
        return loadMutex.withLock {
            val current = System.currentTimeMillis()
            cached?.takeIf { it.expiresAt > current }?.let { return@withLock it.value }
            try {
                val response = httpClient.get(endpoint) {
                    timeout {
                        requestTimeoutMillis = 5_000
                        connectTimeoutMillis = 3_000
                    }
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("exchange rate service returned ${response.status.value}")
                }
                val parsed = parseExchangeRates(response.bodyAsText())
                cached = CachedRates(parsed, current + cacheTtlMs)
                parsed
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                logger.warn("拉取汇率失败: {}", cause.message)
                throw GatewayException(
                    HttpStatusCode.BadGateway,
                    "exchange_rates_unavailable",
                    "暂时无法获取汇率，请稍后重试或手动输入目标币种价格",
                    "api_error",
                )
            }
        }
    }

    internal fun parseExchangeRates(text: String): ExchangeRatesResponse {
        val root = Json.parseToJsonElement(text).jsonObject
        val base = root["base"]?.jsonPrimitive?.content ?: "USD"
        val date = root["date"]?.jsonPrimitive?.content ?: "未知日期"
        val ratesObject = root["rates"]?.jsonObject
            ?: throw IllegalArgumentException("exchange rate response has no rates")
        val rates = linkedMapOf("USD" to "1")
        listOf("CNY", "EUR").forEach { currency ->
            val rate = ratesObject[currency]?.jsonPrimitive?.doubleOrNull
                ?: throw IllegalArgumentException("exchange rate response has no $currency")
            require(rate > 0) { "exchange rate for $currency must be positive" }
            rates[currency] = BigDecimal.valueOf(rate).stripTrailingZeros().toPlainString()
        }
        return ExchangeRatesResponse(
            base = base,
            date = date,
            source = "Frankfurter（欧洲央行参考汇率）",
            rates = rates,
        )
    }

    private data class CachedRates(val value: ExchangeRatesResponse, val expiresAt: Long)
}
