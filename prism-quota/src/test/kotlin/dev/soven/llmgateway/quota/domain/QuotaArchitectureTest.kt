package dev.soven.llmgateway.quota.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 配额模块的依赖边界：策略与内存限流器只允许 JDK、Kotlin 标准库与协程原语；
 * 应用服务可以依赖共享内核的错误类型，但仍不感知渠道、持久化或 HTTP 框架。
 *
 * 额度策略不感知渠道、路由、持久化或 HTTP；一旦引入这些依赖，
 * 「请求是否放行」的判定就会与运行时基础设施重新耦合。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class QuotaArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.quota.domain", "dev.soven.llmgateway.quota.infra")

    private val quotaDomainDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.quota.domain..",
    )

    private val quotaInfraDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "io.ktor..",
        "dev.soven.llmgateway.quota.domain..",
        "dev.soven.llmgateway.quota.infra..",
        "dev.soven.llmgateway.shared.error..",
    )

    @Test
    fun `import covers the quota module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.quota.domain.ClientKeyQuotaLimits" },
            "ArchUnit 未导入配额模块类，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.quota.domain.ClientKeyRecord" },
            "ArchUnit 未导入调用方密钥记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.quota.infra.ClientKeyQuotaService" },
            "ArchUnit 未导入密钥额度服务，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.quota.infra.RequestLimitService" },
            "ArchUnit 未导入请求准入服务，规则会空跑",
        )
    }

    @Test
    fun `quota domain only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.quota.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*quotaDomainDependencies)
            .check(classes)
    }

    @Test
    fun `quota infra only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.quota.infra..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*quotaInfraDependencies)
            .check(classes)
    }
}