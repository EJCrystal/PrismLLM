package dev.soven.prismllm.admin.domain

import dev.soven.prismllm.shared.domain.GatewayProtocol
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("unit")
class SystemPromptSettingsTest {
    private val global = SystemPromptRule("global", "全局", priority = 100, prompt = "global prompt")
    private val protocol = global.copy(
        id = "protocol", name = "协议", scope = SystemPromptScope.PROTOCOLS,
        protocols = listOf("gemini", "messages"), priority = 50, prompt = "protocol prompt",
    )
    private val channel = global.copy(
        id = "channel", name = "渠道", scope = SystemPromptScope.CHANNELS,
        channelIds = listOf("channel-one", "channel-two"), priority = 20, prompt = "channel prompt",
    )
    private val combined = channel.copy(
        id = "combined", scope = SystemPromptScope.CHANNEL_PROTOCOLS,
        protocols = listOf("gemini", "messages"), priority = -1, prompt = "combined prompt",
    )

    @Test
    fun specificityWinsBeforeNumericPriorityAndTargetSetsUseIntersection() {
        val settings = SystemPromptSettingsRecord(true, listOf(global, protocol, channel, combined))
        assertEquals(combined, settings.resolve("channel-two", GatewayProtocol.GEMINI))
        assertEquals(channel, settings.resolve("channel-one", GatewayProtocol.RESPONSES))
        assertEquals(protocol, settings.resolve("other", GatewayProtocol.MESSAGES))
        assertEquals(global, settings.resolve("other", GatewayProtocol.CHAT_COMPLETIONS))
    }

    @Test
    fun disabledAndUnmatchedSettingsDoNotReplaceAnything() {
        val settings = SystemPromptSettingsRecord(false, listOf(global))
        assertNull(settings.resolve("channel-one", GatewayProtocol.GEMINI))
        assertNull(settings.copy(enabled = true, rules = listOf(protocol)).resolve("other", GatewayProtocol.RESPONSES))
        assertNull(settings.copy(enabled = true, rules = listOf(global.copy(enabled = false))).resolve("other", GatewayProtocol.GEMINI))
    }

    @Test
    fun equalSpecificityUsesPriorityThenStableListOrder() {
        val first = global.copy(id = "first", priority = 5)
        val second = global.copy(id = "second", priority = 5)
        val settings = SystemPromptSettingsRecord(true, listOf(first, second))
        assertEquals(first, settings.resolve("other", GatewayProtocol.GEMINI))
        val reordered = settings.copy(rules = listOf(first, second.copy(priority = 6)))
        assertEquals(second.id, reordered.resolve("other", GatewayProtocol.GEMINI)?.id)
    }

    @Test
    fun preservationStopsBroaderRulesAndDeletedTargetsNeverBecomeGlobal() {
        val exception = channel.copy(action = SystemPromptAction.PRESERVE, prompt = "")
        val settings = SystemPromptSettingsRecord(true, listOf(global, exception))
        assertEquals(exception, settings.resolve("channel-one", GatewayProtocol.GEMINI))
        assertNull(settings.replacementFor("channel-one", GatewayProtocol.GEMINI))
        assertEquals(global.prompt, settings.replacementFor("not-listed", GatewayProtocol.GEMINI))
        val emptiedTargets = settings.copy(rules = listOf(channel.copy(channelIds = emptyList())))
        assertNull(emptiedTargets.resolve("other", GatewayProtocol.GEMINI))
    }

    @Test
    fun validationRejectsEmptyScopesInvalidProtocolsAndDuplicateIds() {
        val settings = SystemPromptSettingsRecord(true, listOf(global, protocol, channel, combined))
        assertNull(systemPromptSettingsError(settings))
        assertNotNull(systemPromptSettingsError(settings.copy(rules = listOf(global, global))))
        val invalidRules = listOf(
            protocol.copy(protocols = emptyList()),
            channel.copy(channelIds = emptyList()),
            protocol.copy(protocols = listOf("chat")),
            global.copy(prompt = " \n "),
            global.copy(channelIds = listOf("other")),
        )
        for (invalid in invalidRules) {
            assertNotNull(systemPromptSettingsError(settings.copy(rules = listOf(invalid))))
        }
        val exception = global.copy(action = SystemPromptAction.PRESERVE, prompt = "")
        assertNull(systemPromptSettingsError(settings.copy(rules = listOf(exception))))
    }
}
