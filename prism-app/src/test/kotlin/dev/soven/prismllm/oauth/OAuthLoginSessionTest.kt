package dev.soven.prismllm.oauth

import dev.soven.prismllm.shared.domain.OAuthProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@Tag("unit")
class OAuthLoginSessionTest {
    @Test
    fun `a completed session cannot be bound twice`(): Unit = runBlocking {
        val session = session()

        assertIs<LoginBinding.Bound<String>>(session.bindOnce { "bound" })

        // 已经持有会话引用的晚到请求即使抢到绑定锁，也不能再次执行绑定。
        val replay = session.bindOnce<String> { error("replay must not bind again") }
        assertIs<LoginBinding.Consumed>(replay)
    }

    @Test
    fun `a failed binding keeps the session retryable`(): Unit = runBlocking {
        val session = session()

        assertFailsWith<IllegalStateException> {
            session.bindOnce<String> { throw IllegalStateException("邮箱已绑定") }
        }

        // 绑定失败不消耗会话，管理员处理完冲突后可以用缓存的 token 重试。
        val retried = session.bindOnce { "bound" }
        assertEquals("bound", assertIs<LoginBinding.Bound<String>>(retried).value)
    }

    @Test
    fun `concurrent binding attempts run only one block`(): Unit = runBlocking {
        val session = session()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            session.bindOnce {
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()

        // 第一个请求仍持有绑定锁时，第二个请求直接按已使用处理，不会排队等待后重复写库。
        val second = session.bindOnce<String> { error("concurrent request must not bind") }
        assertIs<LoginBinding.Busy>(second)

        release.complete(Unit)
        assertEquals("first", assertIs<LoginBinding.Bound<String>>(first.await()).value)
    }

    private fun session() = OAuthLoginService.PendingLogin(
        provider = OAuthProvider.CHATGPT,
        accountId = "acct-test",
        name = "test account",
        channelIds = listOf("ch-test"),
        proxyUrl = null,
        credentialFingerprint = "fingerprint",
        verifier = "verifier",
        state = "state",
        redirectUri = "http://localhost:1455/auth/callback",
        createdAt = 0,
        expiresAt = Long.MAX_VALUE,
    )
}
