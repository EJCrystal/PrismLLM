package dev.soven.prismllm.proxy.codeassist

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Antigravity 封禁账号的识别。 */
@Tag("unit")
class CodeAssistSuspensionTest {
    @Test
    fun detectsTheSuspensionBody() {
        val body = """{"error":{"code":403,"message":"This service has been disabled in this account for violation of Terms of Service. Please submit an appeal to continue using this product.","status":"PERMISSION_DENIED"}}"""
        assertTrue(CodeAssistSuspension.isSuspended(body))
    }

    @Test
    fun ignoresOtherFailures() {
        assertFalse(CodeAssistSuspension.isSuspended("""{"error":{"message":"quota exceeded"}}"""))
        assertFalse(CodeAssistSuspension.isSuspended(null))
        assertFalse(CodeAssistSuspension.isSuspended(""))
    }
}
