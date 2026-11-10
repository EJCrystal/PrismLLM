package dev.soven.prismllm.security

import kotlinx.coroutines.sync.Mutex
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 单实例管理面会话。会话只存内存，进程重启后全部失效。
 *
 * @author 文杰
 * @since 2026-10-21
 */
class AdminSessions(private val ttlMillis: Long = 12 * 60 * 60 * 1000L) {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    private val usernameLocks = Array(64) { Mutex() }

    suspend fun <T> withUsernameLock(username: String, block: suspend () -> T): T {
        val lock = usernameLocks[(username.hashCode() and Int.MAX_VALUE) % usernameLocks.size]
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    fun create(username: String): IssuedSession {
        val token = randomToken()
        val session = Session(username, randomToken(), System.currentTimeMillis() + ttlMillis)
        sessions[token] = session
        cleanupExpired()
        return IssuedSession(token, session)
    }

    fun get(token: String?): Session? {
        if (token == null) return null
        val session = sessions[token] ?: return null
        if (session.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token)
            return null
        }
        return session
    }

    fun remove(token: String?) {
        if (token != null) sessions.remove(token)
    }

    fun removeAllForUsername(username: String) {
        sessions.entries.removeIf { it.value.username == username }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /**
     * 管理面会话。
     *
     * @author 文杰
     * @since 2026-10-21
     */
    data class Session(
        val username: String,
        val csrf: String,
        val expiresAt: Long,
    )

    /**
     * 新签发的会话及其 Cookie 值。
     *
     * @author 文杰
     * @since 2026-10-21
     */
    data class IssuedSession(
        val token: String,
        val session: Session,
    )
}
