package dev.soven.prismllm.requestlog.persistence

import dev.soven.prismllm.testsupport.TestClassification
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.support.descriptor.ClassSource
import kotlin.test.assertEquals

@Tag("unit")
class RequestLogPersistenceClassificationTest {
    @Test
    fun `grouped costs repository test remains classified as component`() {
        val plan = TestClassification.discover(selectClass(RequestLogGroupedCostsTest::class.java))
        TestClassification.assertClassified(plan)
        val repositoryClass = TestClassification.identifiers(plan).single {
            (it.source.orElse(null) as? ClassSource)?.className == RequestLogGroupedCostsTest::class.java.name
        }
        assertEquals(setOf("component"), TestClassification.classifications(repositoryClass))
    }
}
