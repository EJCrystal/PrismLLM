package dev.soven.prismllm.testing

import dev.soven.prismllm.testing.classificationfixtures.ConflictingFixture
import dev.soven.prismllm.testing.classificationfixtures.EmptyFixture
import dev.soven.prismllm.testing.classificationfixtures.MissingFixture
import dev.soven.prismllm.testing.classificationfixtures.MissingTemplatesFixture
import dev.soven.prismllm.testing.classificationfixtures.ValidShapesFixture
import dev.soven.prismllm.testsupport.TestClassification.assertClassified
import dev.soven.prismllm.testsupport.TestClassification.classificationTargets
import dev.soven.prismllm.testsupport.TestClassification.classifications
import dev.soven.prismllm.testsupport.TestClassification.discover
import dev.soven.prismllm.testsupport.TestClassification.discoverModule
import dev.soven.prismllm.testsupport.TestClassification.identifiers
import org.junit.jupiter.api.Tag
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 分类是 CI 的执行成本分组，不表示所有 component 都不依赖数据库或本机网络。 */
@Tag("unit")
class TestClassificationTest {
    @Test
    fun `all app tests have exactly one classification and keep integration scopes`() {
        val plan = discoverAppTests()
        assertClassified(plan)
        val classes = identifiers(plan).filter { it.source.orElse(null) is ClassSource }
        classes.forEach { identifier ->
            assertEquals(1, classifications(identifier).size, "class classification: ${identifier.uniqueId}")
        }
        assertTrue(classes.any { (it.source.orElse(null) as ClassSource).className == javaClass.name })
        assertFalse(classes.any { (it.source.orElse(null) as ClassSource).className.startsWith("$FIXTURE_PACKAGE.") })
        assertIntegrationScopes(classes)
    }

    @Test
    fun `discovery supports inheritance nesting parameterized tests templates and factories without execution`() {
        val plan = discover(selectClass(ValidShapesFixture::class.java))
        assertClassified(plan)
        val targets = classificationTargets(plan)
        assertEquals(5, targets.size)
        assertTrue(targets.all { classifications(it) == setOf("component") })
        assertTrue(targets.any { it.uniqueIdObject.lastSegment.type == "test-template" })
        assertTrue(targets.any { it.uniqueIdObject.lastSegment.type == "test-factory" })
        assertTrue(targets.any { (it.source.orElse(null) as? MethodSource)?.methodName == "inherited" })
        assertTrue(targets.any { (it.source.orElse(null) as? MethodSource)?.methodName == "nested" })
    }

    @Test
    fun `missing classification is rejected even with an unrelated scope tag`() {
        val plan = discover(selectClass(MissingFixture::class.java))
        val error = assertFailsWith<AssertionError> { assertClassified(plan) }
        assertTrue(error.message.orEmpty().contains("missing"))
        assertTrue(error.message.orEmpty().contains("classifications=[]"))
    }

    @Test
    fun `method and nested class conflicts include inherited classifications`() {
        val plan = discover(selectClass(ConflictingFixture::class.java))
        val targets = classificationTargets(plan)
        assertEquals(2, targets.size)
        assertTrue(targets.all { classifications(it).size == 2 })
        val error = assertFailsWith<AssertionError> { assertClassified(plan) }
        assertTrue(error.message.orEmpty().contains("methodConflict"))
        assertTrue(error.message.orEmpty().contains("nestedConflict"))
    }

    @Test
    fun `missing classifications on parameterized templates and factories are rejected before invocation`() {
        val plan = discover(selectClass(MissingTemplatesFixture::class.java))
        val targets = classificationTargets(plan)
        assertEquals(3, targets.size)
        assertFalse(targets.any { it.isTest }) // Invocation/dynamic children do not exist during discovery.
        val error = assertFailsWith<AssertionError> { assertClassified(plan) }
        assertTrue(error.message.orEmpty().contains("parameterized"))
        assertTrue(error.message.orEmpty().contains("template"))
        assertTrue(error.message.orEmpty().contains("factory"))
    }

    @Test
    fun `zero discovered tests is an error`() {
        val error = assertFailsWith<AssertionError> {
            assertClassified(discover(selectClass(EmptyFixture::class.java)))
        }
        assertTrue(error.message.orEmpty().contains("No Jupiter tests discovered"))
    }

    private fun discoverAppTests(): TestPlan = discoverModule(javaClass, excludePackages = setOf(FIXTURE_PACKAGE))

    private fun assertIntegrationScopes(classes: List<TestIdentifier>) {
        val byName = classes.associateBy { (it.source.orElse(null) as ClassSource).className }
        val integrationNames = classes.filter { "integration" in it.tags.map { tag -> tag.name } }
            .map { (it.source.orElse(null) as ClassSource).className }.toSet()
        val expectedNames = INTEGRATION_SCOPES.keys.map { "dev.soven.prismllm.integration.$it" }.toSet() +
            "dev.soven.prismllm.api.ProxyAttemptLoggingIntegrationTest"
        assertEquals(expectedNames, integrationNames, "Keep the original 20 integration classes classified as integration")
        INTEGRATION_SCOPES.forEach { (name, scopes) ->
            assertEquals(scopes + "integration", protectedTags(byName.getValue("dev.soven.prismllm.integration.$name")), name)
        }
        assertEquals(setOf("integration", "channel"), protectedTags(byName.getValue("dev.soven.prismllm.api.ProxyAttemptLoggingIntegrationTest")))
    }

    private fun protectedTags(identifier: TestIdentifier): Set<String> = identifier.tags.map { it.name }
        .filter { it == "integration" || it == "channel" || it == "protocol" || it.startsWith("shard-") }.toSet()

    private companion object {
        const val FIXTURE_PACKAGE = "dev.soven.prismllm.testing.classificationfixtures"
        val INTEGRATION_SCOPES = mapOf(
            "AdminChannelIntegrationTest" to setOf("shard-4", "channel"),
            "AdminConsoleIntegrationTest" to setOf("shard-3"),
            "ChannelKeyPoolIntegrationTest" to setOf("shard-1", "channel"),
            "ChannelQuotaIntegrationTest" to setOf("shard-1", "channel"),
            "GeminiNativeIntegrationTest" to setOf("shard-3", "protocol"),
            "GracefulShutdownIntegrationTest" to setOf("shard-4"),
            "IqQuestionBankIntegrationTest" to setOf("shard-3"),
            "KeyAutoDisableIntegrationTest" to setOf("shard-3", "channel"),
            "PricingIntegrationTest" to setOf("shard-4"),
            "ProtocolConversionIntegrationTest" to setOf("shard-2", "protocol"),
            "RateLimitIntegrationTest" to setOf("shard-1", "channel"),
            "RequestAuthenticationIntegrationTest" to setOf("shard-1"),
            "RequestFinalizationIntegrationTest" to setOf("shard-4"),
            "RequestLogAuditIntegrationTest" to setOf("shard-2"),
            "RequestLogQueryIntegrationTest" to setOf("shard-2"),
            "RoutingFailoverIntegrationTest" to setOf("shard-4", "channel"),
            "SubscriptionOAuthIntegrationTest" to setOf("shard-2", "channel"),
            "UpstreamOverridesIntegrationTest" to setOf("shard-1", "channel", "protocol"),
            "UsageReportLatencyIntegrationTest" to setOf("shard-4"),
        )
    }
}
