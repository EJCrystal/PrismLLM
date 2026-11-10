package dev.soven.prismllm.proxy.codeassist

/**
 * Antigravity 账号被封禁时上游返回 403 PERMISSION_DENIED，正文是
 * "This service has been disabled in this account for violation of Terms of Service"。
 * 这不是普通鉴权失败：账号要停两小时起步，且只能靠申诉恢复。
 *
 * @author 文杰
 * @since 2026-11-06
 */
object CodeAssistSuspension {
    /** 被封禁账号的账号级冷却，与 magpie 的 restrictedRest 一致。 */
    const val REST_MS = 2 * 60 * 60 * 1000L

    private const val MARKER = "this service has been disabled in this account"

    fun isSuspended(body: String?): Boolean =
        body != null && body.contains(MARKER, ignoreCase = true) &&
            body.contains("violation of terms of service", ignoreCase = true)
}
