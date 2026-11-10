package dev.soven.llmgateway.requestlog.persistence

/** 请求日志展示与报表需要回查当前名称的实体种类。 */
enum class RequestLogNameKind {
    CLIENT_KEY,
    CHANNEL,
    CHANNEL_KEY,
}

/**
 * 按实体种类批量解析当前名称。调用发生在日志仓储的事务内，
 * 由应用层注入查询渠道、密钥与调用方密钥的实现。
 */
fun interface RequestLogNameResolver {
    fun resolve(kind: RequestLogNameKind, ids: Set<String>): Map<String, String>
}