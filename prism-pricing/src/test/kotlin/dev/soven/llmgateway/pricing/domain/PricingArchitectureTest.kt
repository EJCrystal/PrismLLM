package dev.soven.llmgateway.pricing.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 计价模块的依赖边界：计价类型是纯值对象，只允许 JDK 与 Kotlin 标准库。
 *
 * 价格模式与阶梯价不感知渠道、路由、持久化或 HTTP；解析与计费流程
 * 通过应用层编排，避免计价规则再次散落到基础设施。
 *
 * @author 文杰
 * @since 2026-11-10
 */
class PricingArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.pricing.domain")

    private val pricingDependencies = arrayOf(
        "java..",
        "kotlin..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.pricing.domain..",
    )

    @Test
    fun `import covers the pricing module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.pricing.domain.PricingMode" },
            "ArchUnit 未导入计价模块类，规则会空跑",
        )
    }

    @Test
    fun `pricing module only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.pricing.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*pricingDependencies)
            .check(classes)
    }
}