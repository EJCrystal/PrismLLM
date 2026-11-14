package dev.soven.prismllm.admin.persistence

import dev.soven.prismllm.testsupport.TestClassification
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.support.descriptor.ClassSource
import kotlin.test.assertEquals

@Tag("unit")
class AdminPersistenceClassificationTest {
    @Test
    fun `web search settings repository test remains classified as component`() {
        val plan = TestClassification.discover(selectClass(WebSearchSettingsRepositoryTest::class.java))
        TestClassification.assertClassified(plan)
        val repositoryClass = TestClassification.identifiers(plan).single {
            (it.source.orElse(null) as? ClassSource)?.className == WebSearchSettingsRepositoryTest::class.java.name
        }
        assertEquals(setOf("component"), TestClassification.classifications(repositoryClass))
    }
}
