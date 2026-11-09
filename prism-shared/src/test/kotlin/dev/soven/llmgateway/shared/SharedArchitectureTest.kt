package dev.soven.llmgateway.shared

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 共享内核的依赖边界：只允许基础库，禁止引用业务模块。
 *
 * 共享内核被所有上下文依赖，任何业务包的反向引用都会把模块拆分成环；
 * SPI 是插件作者的稳定入口，保持自包含，避免插件被迫拉入内核实现。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class SharedArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.shared", "dev.soven.llmgateway.spi")

    private val sharedDependencies = arrayOf(
        "java..",
        "javax.crypto..",
        "kotlin..",
        "kotlinx.serialization..",
        "io.ktor.http..",
        "org.bouncycastle..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.shared..",
        "dev.soven.llmgateway.spi..",
    )

    @Test
    fun `import covers the shared kernel`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.shared.domain.GatewayProtocol" },
            "ArchUnit 未导入共享内核类，规则会空跑",
        )
    }

    @Test
    fun `shared kernel only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.shared..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*sharedDependencies)
            .check(classes)
    }

    @Test
    fun `spi stays self contained`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.spi..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..",
                "kotlin..",
                "org.jetbrains.annotations..",
                "dev.soven.llmgateway.spi..",
            )
            .check(classes)
    }

    @Test
    fun `shared subpackages are free of cycles`() {
        slices().matching("dev.soven.llmgateway.shared.(*)..")
            .should().beFreeOfCycles()
            .check(classes)
    }
}