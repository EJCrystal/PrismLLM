package dev.soven.prismllm.quota.persistence

import dev.soven.prismllm.quota.domain.ClientKeyQuotaUsage
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.math.BigDecimal

/**
 * 调用方密钥按自然日累计的 token 与费用账本。
 */
class ClientKeyQuotaRepository(private val database: Database) {
    suspend fun usage(clientKeyId: String, period: Long): ClientKeyQuotaUsage = suspendTransaction(database) {
        ClientKeyQuotaUsages
            .selectAll()
            .where { (ClientKeyQuotaUsages.clientKeyId eq clientKeyId) and (ClientKeyQuotaUsages.period eq period) }
            .singleOrNull()
            ?.let(::toUsage)
            ?: ClientKeyQuotaUsage()
    }

    suspend fun addUsage(
        clientKeyId: String,
        period: Long,
        tokens: Long,
        usdCost: BigDecimal,
    ): Unit = suspendTransaction(database) {
        // 结算不是幂等操作；数据库异常直接交给调用方，不能自动重试累加。
        maxAttempts = 1
        // 收尾不响应协程取消；仅限制本事务的语句（含锁等待），避免卡住后续日志与许可归还。
        exec("SET LOCAL statement_timeout = '3s'")
        val args = listOf<Pair<IColumnType<*>, Any?>>(
            TextColumnType() to clientKeyId,
            LongColumnType() to period,
            LongColumnType() to tokens.coerceAtLeast(0L),
            DecimalColumnType(18, 8) to usdCost,
        )
        // 首次插入与已有行增量都由 PostgreSQL 在同一条语句中原子完成，避免先读后写丢失用量。
        exec(
            """
                insert into client_key_quota_usage (client_key_id, period, tokens, usd_cost)
                values (?, ?, ?, ?)
                on conflict (client_key_id, period) do update
                set tokens = client_key_quota_usage.tokens + excluded.tokens,
                    usd_cost = client_key_quota_usage.usd_cost + excluded.usd_cost
            """.trimIndent(),
            args,
        )
        Unit
    }

    private fun toUsage(row: ResultRow) = ClientKeyQuotaUsage(
        dailyTokens = row[ClientKeyQuotaUsages.tokens],
        dailyUsdCost = row[ClientKeyQuotaUsages.usdCost],
    )
}