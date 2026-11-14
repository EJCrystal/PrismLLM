package dev.soven.prismllm.testsupport

import dev.soven.prismllm.testsupport.discoveryfixtures.EmptyDiscoveryProbe
import dev.soven.prismllm.testsupport.discoveryfixtures.MixedClassificationProbe
import dev.soven.prismllm.testsupport.moduleroot.ModuleRootProbe
import org.junit.jupiter.api.Tag
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("unit")
class TestClassificationTest {
    @Test
    fun `explicit selector exposes classifications and actionable failures without execution`() {
        val plan = TestClassification.discover(selectClass(MixedClassificationProbe::class.java))
        val targets = TestClassification.classificationTargets(plan)
        val byMethod = targets.associateBy { (it.source.orElse(null) as MethodSource).methodName }

        assertEquals(setOf("classified", "unclassified", "conflicting"), byMethod.keys)
        assertEquals(setOf("component"), TestClassification.classifications(byMethod.getValue("classified")))
        assertEquals(emptySet(), TestClassification.classifications(byMethod.getValue("unclassified")))
        assertEquals(setOf("browser", "unit"), TestClassification.classifications(byMethod.getValue("conflicting")))
        assertTrue(TestClassification.identifiers(plan).containsAll(targets))
        assertTrue(TestClassification.identifiers(plan).containsAll(plan.roots))

        val error = assertFailsWith<AssertionError> { TestClassification.assertClassified(plan) }
        val message = error.message.orEmpty()
        assertTrue(message.contains("unclassified") && message.contains("classifications=[]"))
        assertTrue(message.contains("conflicting") && message.contains("classifications=[browser, unit]"))
        assertTrue(message.contains("tags=[channel, shard-2]"))
        assertFalse(message.contains(byMethod.getValue("classified").uniqueId))
    }

    @Test
    fun `empty discovery cannot pass the classification gate`() {
        val plan = TestClassification.discover(selectClass(EmptyDiscoveryProbe::class.java))
        assertTrue(TestClassification.classificationTargets(plan).isEmpty())
        val error = assertFailsWith<AssertionError> { TestClassification.assertClassified(plan) }
        assertEquals("No Jupiter tests discovered; classification coverage must not be empty", error.message)
    }

    @Test
    fun `module discovery follows the anchor root and excludes only requested packages`() {
        val excluded = setOf(MixedClassificationProbe::class.java.packageName)
        val plan = TestClassification.discoverModule(javaClass, excludePackages = excluded)
        TestClassification.assertClassified(plan)
        val classes = TestClassification.identifiers(plan).mapNotNull {
            (it.source.orElse(null) as? ClassSource)?.className
        }.toSet()

        assertTrue(javaClass.name in classes)
        assertTrue(ModuleRootProbe::class.java.name in classes)
        assertFalse(classes.any { it.startsWith("${excluded.single()}.") })
        val siblingPlan = TestClassification.discoverModule(ModuleRootProbe::class.java, excludePackages = excluded)
        assertEquals(
            TestClassification.classificationTargets(plan).map { it.uniqueId }.toSet(),
            TestClassification.classificationTargets(siblingPlan).map { it.uniqueId }.toSet(),
        )
        val error = assertFailsWith<AssertionError> {
            TestClassification.discoverModule(org.junit.jupiter.api.Test::class.java)
        }
        assertTrue(error.message.orEmpty().contains("Expected a compiled test classes directory"))
    }
}
