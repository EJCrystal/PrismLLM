package dev.soven.llmgateway.requestlog.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 请求日志模块的依赖边界：日志记录、尝试明细与编解码只做纯数据转换，
 * 只允许共享内核、计价上下文的只读类型、序列化注解与标准库。
 *
 * 日志只追加、不感知持久化与 HTTP；写入队列、仓储和报表留在应用层，
 * 后续再按端口迁入。
 *
 * @author 文杰
 * @since 2026-11-10
 */
class RequestLogArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.requestlog.domain", "dev.soven.llmgateway.requestlog.infra")

    private val requestLogDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.requestlog.domain..",
        "dev.soven.llmgateway.shared.domain..",
        "dev.soven.llmgateway.pricing.domain..",
    )

    /** infra 可以依赖本模块的领域类型，反过来不允许。 */
    private val requestLogInfraDependencies = requestLogDependencies + "dev.soven.llmgateway.requestlog.infra.."

    @Test
    fun `import covers the request log module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.requestlog.domain.RequestLogRecord" },
            "ArchUnit 未导入请求日志模块类，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.requestlog.infra.UpstreamAttemptCodec" },
            "ArchUnit 未导入尝试明细编解码，规则会空跑",
        )
    }

    @Test
    fun `request log domain only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.requestlog.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*requestLogDependencies)
            .check(classes)
    }

    @Test
    fun `request log infra only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.requestlog.infra..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*requestLogInfraDependencies)
            .check(classes)
    }
}