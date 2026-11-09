package dev.soven.llmgateway.resilience

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 弹性模块的依赖边界：只允许共享内核、协程、日志门面与冷却快照持久化，禁止反向引用渠道、路由或应用层。
 *
 * 排序器通过 [dev.soven.llmgateway.shared.domain.RouteCandidateOrderingView] 认识候选，
 * 冷却与密钥准入通过端口与上层交互；一旦直接依赖 channel/routing/app，模块拆分就重新变成环。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class ResilienceArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.resilience")

    private val resilienceDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "org.slf4j..",
        "dev.soven.llmgateway.shared..",
        "dev.soven.llmgateway.resilience..",
    )

    @Test
    fun `import covers the resilience module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.resilience.TierSharing" },
            "ArchUnit 未导入弹性模块类，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.resilience.persistence.CooldownStateRepository" },
            "ArchUnit 未导入冷却状态仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.resilience.persistence.CooldownStates" },
            "ArchUnit 未导入冷却状态表，规则会空跑",
        )
    }

    @Test
    fun `resilience module only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.resilience..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*resilienceDependencies)
            .check(classes)
    }

    @Test
    fun `resilience subpackages are free of cycles`() {
        slices().matching("dev.soven.llmgateway.resilience.(*)..")
            .should().beFreeOfCycles()
            // 当前弹性类都在根包，尚无子包切片；规则在引入子包后自动生效。
            .allowEmptyShould(true)
            .check(classes)
    }
}