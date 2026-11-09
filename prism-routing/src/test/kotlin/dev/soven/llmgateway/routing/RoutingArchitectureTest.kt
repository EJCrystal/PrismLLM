package dev.soven.llmgateway.routing

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 路由模块的依赖边界：允许共享内核与渠道模块，禁止反向引用应用层与其它上下文。
 *
 * 路由是数据面编排者，只能通过端口调用冷却、订阅账号与协议转换；
 * 一旦直接依赖 app/api，模块拆分就重新变成环。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class RoutingArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.routing")

    private val routingDependencies = arrayOf(
        "java..",
        "kotlin..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.shared..",
        "dev.soven.llmgateway.channel..",
        "dev.soven.llmgateway.protocol..",
        "dev.soven.llmgateway.routing..",
    )

    @Test
    fun `import covers the routing module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.routing.domain.candidate.RouteCandidate" },
            "ArchUnit 未导入路由模块类，规则会空跑",
        )
    }

    @Test
    fun `routing module only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.routing..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*routingDependencies)
            .check(classes)
    }

    @Test
    fun `routing subpackages are free of cycles`() {
        slices().matching("dev.soven.llmgateway.routing.(*)..")
            .should().beFreeOfCycles()
            .check(classes)
    }
}