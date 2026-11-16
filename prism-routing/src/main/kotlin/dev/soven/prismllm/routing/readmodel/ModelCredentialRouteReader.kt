package dev.soven.prismllm.routing.readmodel

/** 凭据类型是身份的一部分；静态密钥与订阅账号可以具有相同 ID。 */
enum class CredentialRouteType(val value: String) {
    STATIC("static"),
    OAUTH("oauth"),
    ;

    companion object {
        fun fromValue(value: String): CredentialRouteType? = entries.firstOrNull { it.value == value }
    }
}

/** 模型路由中可持久化的凭据身份；上游模型变体由目录在读取时展开。 */
data class CredentialRouteIdentity(
    val channelId: String,
    val credentialType: CredentialRouteType,
    val credentialId: String,
)

/** entries 的顺序即管理员保存的尝试顺序；已配置的空列表不回退到其它凭据。 */
data class ModelCredentialRouteSnapshot(
    val modelId: String,
    val revision: Long,
    val entries: List<CredentialRouteIdentity>,
    val configured: Boolean = true,
)

/** 应用组合根提供的只读端口；routing 不依赖跨上下文持久化实现。 */
interface ModelCredentialRouteReader {
    suspend fun find(modelId: String): ModelCredentialRouteSnapshot?
    suspend fun list(): List<ModelCredentialRouteSnapshot>
}
