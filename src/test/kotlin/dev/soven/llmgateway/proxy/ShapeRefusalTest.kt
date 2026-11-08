package dev.soven.llmgateway.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeRefusalTest {
    @Test
    fun `422 is always a shape refusal`() {
        assertTrue(ShapeRefusal.matches(422, """{"error":{"message":"unprocessable"}}"""))
        assertTrue(
            ShapeRefusal.matches(
                422,
                """{"error":{"message":"Failed to deserialize the JSON body into the target type: messages[0].role: unknown variant `developer`"}}""",
            ),
        )
    }

    @Test
    fun `400 matches known shape vocabulary only`() {
        assertTrue(ShapeRefusal.matches(400, """{"error":{"message":"Unknown parameter: 'foo'"}}"""))
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"Additional properties are not allowed ('x' was unexpected)"}}""",
            ),
        )
        assertTrue(ShapeRefusal.matches(400, """{"error":{"message":"Unknown variant `developer`, expected one of ..."}}"""))
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"Failed to deserialize the JSON body into the target type"}}""",
            ),
        )
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"sub2api: Upstream request failed","type":"upstream_error"}}""",
            ),
        )
        assertFalse(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"Upstream request failed, insufficient_quota","type":"upstream_error"}}""",
            ),
        )
        // 这些字段名包含 quota/credit/balance 词根，但仍是未知字段，属于形状拒绝。
        assertTrue(ShapeRefusal.matches(400, """{"error":{"message":"unknown parameter: credit_limit"}}"""))
        assertTrue(ShapeRefusal.matches(400, """{"error":{"message":"unknown field: balance_target"}}"""))
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"additional properties are not allowed: quota_exceeded_policy"}}""",
            ),
        )
        // 缺失必填字段是所有上游都会拒绝的请求错误，不属于形状不兼容。
        assertFalse(ShapeRefusal.matches(400, """{"error":{"message":"messages: field required"}}"""))
        assertFalse(ShapeRefusal.matches(400, """{"error":{"message":"Invalid parameter: max_tokens must be positive"}}"""))
    }

    @Test
    fun `400 acknowledging a refused reasoning field is a shape refusal`() {
        // 换一个不需要回传思考内容的候选渠道就能服务，所以是可重试的形状问题。
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API."}}""",
            ),
        )
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"The `content[].thinking` in the thinking mode must be passed back to the API."}}""",
            ),
        )
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"Missing required parameter: 'reasoning_content'","type":"invalid_request_error"}}""",
            ),
        )
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"reasoning_content is required for assistant messages"}}""",
            ),
        )
        // Mistral 的 extra_forbidden 已被既有词表覆盖。
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"Extra inputs are not permitted","type":"extra_forbidden"}}""",
            ),
        )
    }

    @Test
    fun `400 rejecting the developer role is a shape refusal`() {
        assertTrue(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"developer is not one of ['system', 'assistant', 'user', 'tool', 'function']"}}""",
            ),
        )
        // 泛化的角色拒绝不猜，避免把真正的请求错误当成形状问题。
        assertFalse(
            ShapeRefusal.matches(
                400,
                """{"error":{"message":"Invalid value for role: 'wizard'"}}""",
            ),
        )
    }

    @Test
    fun `other statuses never match`() {
        assertFalse(ShapeRefusal.matches(500, "unknown variant developer"))
        assertFalse(ShapeRefusal.matches(404, "failed to deserialize"))
    }
}
