package dev.soven.prismllm.quota.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证访问范围的空白名单不能退化为不限制模式。 */
class ClientKeyAccessPolicyTest {
    @Test
    fun `only three null scopes are unrestricted`() {
        assertTrue(ClientKeyAccessPolicy().isUnrestricted)
        assertFalse(ClientKeyAccessPolicy(allowedChannelIds = emptySet()).isUnrestricted)
        assertFalse(ClientKeyAccessPolicy(allowedChannelKeys = emptySet()).isUnrestricted)
        assertFalse(ClientKeyAccessPolicy(allowedModelIds = emptySet()).isUnrestricted)
        assertFalse(ClientKeyAccessPolicy(allowedModelIds = setOf("Model-A")).isUnrestricted)
    }

    @Test
    fun `channel key identity includes its owning channel`() {
        val keys = setOf(
            ClientKeyAllowedChannelKey("channel-a", "key"),
            ClientKeyAllowedChannelKey("channel-b", "key"),
            ClientKeyAllowedChannelKey("channel-a", "key"),
        )
        assertEquals(2, keys.size)
    }
}
