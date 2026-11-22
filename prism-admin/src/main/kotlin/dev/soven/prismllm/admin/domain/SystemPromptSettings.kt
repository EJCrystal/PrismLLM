package dev.soven.prismllm.admin.domain

import dev.soven.prismllm.shared.domain.GatewayProtocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SystemPromptScope(val specificity: Int) {
    @SerialName("global") GLOBAL(0),
    @SerialName("protocols") PROTOCOLS(1),
    @SerialName("channels") CHANNELS(2),
    @SerialName("channel_protocols") CHANNEL_PROTOCOLS(3),
}

@Serializable
enum class SystemPromptAction {
    @SerialName("replace") REPLACE,
    @SerialName("preserve") PRESERVE,
}

@Serializable
data class SystemPromptRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val scope: SystemPromptScope = SystemPromptScope.GLOBAL,
    val protocols: List<String> = emptyList(),
    val channelIds: List<String> = emptyList(),
    val priority: Int = 0,
    val action: SystemPromptAction = SystemPromptAction.REPLACE,
    val prompt: String = "",
) {
    fun matches(channelId: String, protocol: GatewayProtocol): Boolean = enabled && when (scope) {
        SystemPromptScope.GLOBAL -> true
        SystemPromptScope.PROTOCOLS -> protocol.value in protocols
        SystemPromptScope.CHANNELS -> channelId in channelIds
        SystemPromptScope.CHANNEL_PROTOCOLS -> channelId in channelIds && protocol.value in protocols
    }
}

@Serializable
data class SystemPromptSettingsRecord(
    val enabled: Boolean = false,
    val rules: List<SystemPromptRule> = emptyList(),
    val updatedAt: Long = 0,
) {
    fun resolve(channelId: String, protocol: GatewayProtocol): SystemPromptRule? {
        if (!enabled) return null
        return rules.withIndex().filter { it.value.matches(channelId, protocol) }
            .maxWithOrNull(
                compareBy<IndexedValue<SystemPromptRule>>(
                    { it.value.scope.specificity }, { it.value.priority }, { -it.index },
                ),
            )?.value
    }

    fun replacementFor(channelId: String, protocol: GatewayProtocol): String? =
        resolve(channelId, protocol)?.takeIf { it.action == SystemPromptAction.REPLACE }?.prompt
}

fun systemPromptSettingsError(settings: SystemPromptSettingsRecord): String? {
    if (settings.rules.map { it.id }.toSet().size != settings.rules.size) return "规则 ID 不能重复"
    for (rule in settings.rules) {
        if (rule.id.isBlank() || rule.id.length > 80) return "规则 ID 无效"
        if (rule.name.isBlank() || rule.name.length > 100) return "规则名称必须为 1 至 100 个字符"
        if (rule.action == SystemPromptAction.REPLACE && rule.prompt.isBlank()) return "替换规则的系统提示词不能为空"
        if (rule.protocols.any { GatewayProtocol.fromValue(it) == null }) return "出口协议无效"
        if (rule.protocols.distinct().size != rule.protocols.size || rule.channelIds.distinct().size != rule.channelIds.size) {
            return "规则中的协议或渠道不能重复"
        }
        val requiresProtocols = rule.scope in setOf(SystemPromptScope.PROTOCOLS, SystemPromptScope.CHANNEL_PROTOCOLS)
        val requiresChannels = rule.scope in setOf(SystemPromptScope.CHANNELS, SystemPromptScope.CHANNEL_PROTOCOLS)
        if (requiresProtocols != rule.protocols.isNotEmpty()) return "请按作用域选择出口协议"
        if (requiresChannels != rule.channelIds.isNotEmpty() || rule.channelIds.any { it.isBlank() }) return "请按作用域选择渠道"
    }
    return null
}
