package dev.soven.llmgateway.channel.persistence

import kotlinx.serialization.json.JsonObject

/**
 * 密钥状态变更的审计写入端口，由应用层接到管理面审计表。
 *
 * 实现必须写入调用方已开启的当前事务：停用/恢复与对应审计要么一起提交，要么一起回滚。
 * 写成独立事务会让「已停用但没有审计记录」的状态泄漏到线上。
 *
 * @author 文杰
 * @since 2026-11-10
 */
fun interface KeyStateAudit {
    fun record(
        actor: String?,
        action: String,
        method: String,
        path: String,
        statusCode: Int,
        requestId: String,
        sourceIp: String?,
        errorCode: String?,
        details: JsonObject?,
    )
}
