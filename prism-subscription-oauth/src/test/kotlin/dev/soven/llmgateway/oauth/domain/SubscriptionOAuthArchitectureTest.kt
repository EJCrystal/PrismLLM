package dev.soven.llmgateway.oauth.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 订阅账号模块的依赖边界：只允许共享内核，禁止反向引用渠道、路由或应用层。
 *
 * 账号实现共享内核的 [dev.soven.llmgateway.shared.domain.UpstreamCredential] 角色接口，
 * 业务字段与策略留在本模块；一旦直接依赖 channel/routing/app，模块拆分就重新变成环。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class SubscriptionOAuthArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.oauth.domain")

    private val subscriptionOAuthDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.shared..",
        "dev.soven.llmgateway.oauth.domain..",
    )

    @Test
    fun `import covers the subscription oauth module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.oauth.domain.OAuthAccountRecord" },
            "ArchUnit 未导入订阅账号模块类，规则会空跑",
        )
    }

    @Test
    fun `subscription oauth module only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.oauth.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*subscriptionOAuthDependencies)
            .check(classes)
    }
}