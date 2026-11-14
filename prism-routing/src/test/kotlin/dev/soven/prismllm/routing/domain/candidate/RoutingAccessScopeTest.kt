package dev.soven.prismllm.routing.domain.candidate

import dev.soven.prismllm.channel.domain.ChannelKeyRecord
import dev.soven.prismllm.channel.domain.key.ChannelKeyState
import dev.soven.prismllm.shared.domain.UpstreamCredential
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证路由本地授权身份与模式语义；真实目录及候选效果由应用集成测试覆盖。 */
class RoutingAccessScopeTest {
    @Test
    fun `credential identity requires the selected channel as well as key ID`() {
        val scope = RoutingAccessScope(allowedChannelKeys = setOf(RoutingAllowedChannelKey("A", "shared-key")))
        assertTrue(scope.allowsCredential("A", staticKey("A", "shared-key")))
        assertFalse(scope.allowsCredential("B", staticKey("B", "shared-key")))
        assertFalse(scope.allowsCredential("A", staticKey("A", "other-key")))
    }

    @Test
    fun `multiple selections form union while channel and key dimensions intersect`() {
        val scope = RoutingAccessScope(allowedChannelIds = setOf("A", "B"), allowedChannelKeys = setOf(
            RoutingAllowedChannelKey("A", "one"), RoutingAllowedChannelKey("B", "two"), RoutingAllowedChannelKey("C", "three"),
        ))
        assertTrue(scope.allowsCredential("A", staticKey("A", "one")))
        assertTrue(scope.allowsCredential("B", staticKey("B", "two")))
        assertFalse(scope.allowsCredential("C", staticKey("C", "three")))
        assertFalse(scope.allowsCredential("A", staticKey("A", "two")))
    }

    @Test
    fun `static whitelist rejects OAuth credentials even when ID collides`() {
        val oauth = OAuthCredential("selected")
        assertTrue(RoutingAccessScope().allowsCredential("A", oauth))
        assertTrue(RoutingAccessScope(allowedChannelIds = setOf("A")).allowsCredential("A", oauth))
        val scope = RoutingAccessScope(allowedChannelKeys = setOf(RoutingAllowedChannelKey("A", "selected")))
        assertFalse(scope.allowsCredential("A", oauth))
        assertTrue(scope.allowsCredential("A", staticKey("A", "selected")))
    }

    @Test
    fun `configured disabled key stays authorized independently of availability`() {
        val key = staticKey("A", "disabled").copy(state = ChannelKeyState.AUTO_PERMANENT)
        assertFalse(key.isRoutable(100))
        assertTrue(RoutingAccessScope(allowedChannelKeys = setOf(RoutingAllowedChannelKey("A", "disabled")))
            .allowsCredential("A", key))
    }

    @Test
    fun `empty dimensions deny while null remains unrestricted and model IDs match exactly`() {
        val key = staticKey("A", "one")
        assertTrue(RoutingAccessScope().allowsModel("PublicModel"))
        assertTrue(RoutingAccessScope().allowsCredential("A", key))
        assertFalse(RoutingAccessScope(allowedChannelIds = emptySet()).allowsCredential("A", key))
        assertFalse(RoutingAccessScope(allowedChannelKeys = emptySet()).allowsCredential("A", key))
        assertFalse(RoutingAccessScope(allowedModelIds = emptySet()).allowsModel("PublicModel"))
        val scope = RoutingAccessScope(allowedModelIds = setOf("PublicModel", "second"))
        assertTrue(scope.allowsModel("PublicModel"))
        assertTrue(scope.allowsModel("second"))
        assertFalse(scope.allowsModel("publicmodel"))
        assertFalse(scope.allowsModel("raw-upstream"))
        assertFalse(RoutingAccessScope(allowedModelIds = setOf("*")).allowsModel("PublicModel"))
    }

    /** 创建不依赖数据库的真实静态渠道密钥记录。 */
    private fun staticKey(channelId: String, id: String): ChannelKeyRecord = ChannelKeyRecord(
        id = id, channelId = channelId, name = id, credentialCiphertext = "cipher", credentialNonce = "nonce",
        credentialKeyVersion = 1, state = ChannelKeyState.ACTIVE, priority = 0, models = listOf("raw-upstream"),
        createdAt = 0, updatedAt = 0,
    )

    /** 通过共享凭据端口提供 OAuth 身份，避免路由测试依赖订阅账号上下文。 */
    private data class OAuthCredential(override val id: String) : UpstreamCredential {
        override val name: String = id
        override val priority: Int = 0
        override val models: List<String> = listOf("raw-upstream")
        override val enabled: Boolean = true
        override val isOAuth: Boolean = true
        override fun supports(upstreamModel: String): Boolean = upstreamModel in models
        override fun isRoutable(now: Long): Boolean = true
    }
}
