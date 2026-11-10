package dev.soven.prismllm.pricing.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 计价模块的依赖边界：计价类型是纯值对象，只允许 JDK 与 Kotlin 标准库；
 * 持久化包可加序列化与 Exposed，但仍不感知渠道、路由或应用层。
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
        .importPackages("dev.soven.prismllm.pricing.domain", "dev.soven.prismllm.pricing.persistence")

    private val pricingDependencies = arrayOf(
        "java..",
        "kotlin..",
        "org.jetbrains.annotations..",
        "dev.soven.prismllm.pricing.domain..",
    )

    private val pricingPersistenceDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "dev.soven.prismllm.pricing.domain..",
        "dev.soven.prismllm.pricing.persistence..",
    )

    @Test
    fun `import covers the pricing module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.domain.PricingMode" },
            "ArchUnit 未导入计价模块类，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.persistence.ModelDefaultPriceRepository" },
            "ArchUnit 未导入默认价仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.persistence.PricingTierCodec" },
            "ArchUnit 未导入阶梯价编解码器，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.persistence.ModelDefaultPrices" },
            "ArchUnit 未导入默认价表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.domain.ChannelNameLookup" },
            "ArchUnit 未导入渠道名称查询端口，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.persistence.ModelPriceRepository" },
            "ArchUnit 未导入模型单价仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.pricing.persistence.ModelPrices" },
            "ArchUnit 未导入模型单价表，规则会空跑",
        )
    }

    @Test
    fun `pricing domain only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.prismllm.pricing.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*pricingDependencies)
            .check(classes)
    }

    @Test
    fun `pricing persistence only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.prismllm.pricing.persistence..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*pricingPersistenceDependencies)
            .check(classes)
    }
}