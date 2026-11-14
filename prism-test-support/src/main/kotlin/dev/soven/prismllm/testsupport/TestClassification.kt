package dev.soven.prismllm.testsupport

import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.discovery.ClassNameFilter
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots
import org.junit.platform.engine.discovery.PackageNameFilter
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.discovery.LauncherDiscoveryListeners
import java.nio.file.Files
import java.nio.file.Path

/** JUnit 的有效分类发现工具，不执行测试，也不依赖 Surefire 的过滤结果。 */
object TestClassification {
    private val CLASSIFICATIONS = setOf("unit", "component", "browser", "integration")

    fun discover(vararg selectors: DiscoverySelector, excludePackages: Set<String> = emptySet()): TestPlan {
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(*selectors)
            .filters(EngineFilter.includeEngines("junit-jupiter"), ClassNameFilter.includeClassNamePatterns(".*"))
            .enableImplicitConfigurationParameters(false)
            .listeners(LauncherDiscoveryListeners.abortOnFailure())
        if (excludePackages.isNotEmpty()) {
            request.filters(PackageNameFilter.excludePackageNames(*excludePackages.toTypedArray()))
        }
        val launcher = LauncherFactory.create(
            LauncherConfig.builder()
                .enableLauncherSessionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .enablePostDiscoveryFilterAutoRegistration(false)
                .build(),
        )
        // Never execute this plan: constructors, lifecycle methods, parameter providers and factories stay untouched.
        return launcher.discover(request.build())
    }

    /** Discover only the anchor's compiled test directory, not dependency jars or the anchor's package alone. */
    fun discoverModule(anchor: Class<*>, excludePackages: Set<String> = emptySet()): TestPlan {
        val location = anchor.protectionDomain.codeSource?.location
            ?: throw AssertionError("Expected a compiled test classes directory for ${anchor.name}")
        val root = Path.of(location.toURI())
        if (!Files.isDirectory(root)) {
            throw AssertionError("Expected a compiled test classes directory: $root")
        }
        return discover(*selectClasspathRoots(setOf(root)).toTypedArray(), excludePackages = excludePackages)
    }

    fun identifiers(plan: TestPlan): List<TestIdentifier> =
        plan.roots.flatMap { listOf(it) + plan.getDescendants(it) }

    fun classificationTargets(plan: TestPlan): List<TestIdentifier> = identifiers(plan).filter {
        it.isTest || it.source.orElse(null) is MethodSource
    }

    fun classifications(identifier: TestIdentifier): Set<String> =
        identifier.tags.map { it.name }.toSet().intersect(CLASSIFICATIONS)

    fun assertClassified(plan: TestPlan) {
        val targets = classificationTargets(plan)
        if (targets.isEmpty()) {
            throw AssertionError("No Jupiter tests discovered; classification coverage must not be empty")
        }
        // Jupiter's effective descriptor tags already include superclass, enclosing @Nested class and method tags.
        val failures = targets.filter { classifications(it).size != 1 }
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.joinToString("\n") {
                "${it.uniqueId}: classifications=${classifications(it).sorted()}, tags=${it.tags.map { tag -> tag.name }.sorted()}"
            })
        }
    }
}
