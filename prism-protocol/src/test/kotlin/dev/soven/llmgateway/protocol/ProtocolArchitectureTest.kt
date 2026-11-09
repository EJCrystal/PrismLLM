package dev.soven.llmgateway.protocol

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 协议转换模块必须保持无状态、无 IO 的纯函数库边界。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class ProtocolArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.protocol")

    @Test
    fun `import covers the protocol module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.protocol.ProtocolConverter" },
            "ArchUnit 未导入协议模块类，规则会空跑",
        )
    }

    @Test
    fun `protocol module stays a pure conversion library`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.protocol..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..",
                "kotlin..",
                "kotlinx.serialization..",
                "org.jetbrains.annotations..",
                "dev.soven.llmgateway.shared..",
                "dev.soven.llmgateway.protocol..",
            )
            .check(classes)
    }
}