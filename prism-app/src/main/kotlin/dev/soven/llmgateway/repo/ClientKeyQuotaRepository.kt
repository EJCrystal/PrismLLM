package dev.soven.llmgateway.repo

import dev.soven.llmgateway.db.ClientKeyQuotaUsage as ClientKeyQuotaUsageTable
import dev.soven.llmgateway.quota.domain.ClientKeyQuotaUsage
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal

/**
 * 调用方密钥按自然日累计的 token 与费用账本。
 */
class ClientKeyQuotaRepository(private val database: Database) {
    suspend fun usage(clientKeyId: String, period: Long): ClientKeyQuotaUsage = suspendTransaction(database) {
        ClientKeyQuotaUsageTable
            .selectAll()
            .where { (ClientKeyQuotaUsageTable.clientKeyId eq clientKeyId) and (ClientKeyQuotaUsageTable.period eq period) }
            .singleOrNull()
            ?.let(::toUsage)
            ?: ClientKeyQuotaUsage()
    }

    suspend fun addUsage(
        clientKeyId: String,
        period: Long,
        tokens: Long,
        costs: Map<String, BigDecimal>,
    ) = suspendTransaction(database) {
        val existing = ClientKeyQuotaUsageTable
            .selectAll()
            .where { (ClientKeyQuotaUsageTable.clientKeyId eq clientKeyId) and (ClientKeyQuotaUsageTable.period eq period) }
            .singleOrNull()
        if (existing == null) {
            ClientKeyQuotaUsageTable.insert {
                it[this.clientKeyId] = clientKeyId
                it[this.period] = period
                it[this.tokens] = tokens.coerceAtLeast(0)
                it[usdCost] = costs["USD"] ?: BigDecimal.ZERO
                it[cnyCost] = costs["CNY"] ?: BigDecimal.ZERO
                it[eurCost] = costs["EUR"] ?: BigDecimal.ZERO
            }
        } else {
            ClientKeyQuotaUsageTable.update({
                (ClientKeyQuotaUsageTable.clientKeyId eq clientKeyId) and (ClientKeyQuotaUsageTable.period eq period)
            }) {
                it[this.tokens] = existing[this.tokens] + tokens.coerceAtLeast(0)
                it[usdCost] = existing[usdCost] + (costs["USD"] ?: BigDecimal.ZERO)
                it[cnyCost] = existing[cnyCost] + (costs["CNY"] ?: BigDecimal.ZERO)
                it[eurCost] = existing[eurCost] + (costs["EUR"] ?: BigDecimal.ZERO)
            }
        }
    }

    private fun toUsage(row: ResultRow) = ClientKeyQuotaUsage(
        dailyTokens = row[ClientKeyQuotaUsageTable.tokens],
        dailyCosts = mapOf(
            "USD" to row[ClientKeyQuotaUsageTable.usdCost],
            "CNY" to row[ClientKeyQuotaUsageTable.cnyCost],
            "EUR" to row[ClientKeyQuotaUsageTable.eurCost],
        ),
    )
}
