package dev.soven.prismllm.requestlog.domain

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 请求日志模块的依赖边界：日志记录、尝试明细、写入队列与编解码只做纯数据转换，
 * 只允许共享内核、计价上下文的只读类型、序列化/协程调度、日志门面与标准库。
 *
 * 日志只追加、不感知持久化与 HTTP；写入队列依赖注入的插入与定价函数，
 * 仓储和报表留在应用层，后续再按端口迁入。
 *
 * @author 文杰
 * @since 2026-11-10
 */
class RequestLogArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(
            "dev.soven.prismllm.requestlog.domain",
            "dev.soven.prismllm.requestlog.infra",
            "dev.soven.prismllm.requestlog.persistence",
        )

    private val requestLogDomainDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "org.jetbrains.annotations..",
        "dev.soven.prismllm.requestlog.domain..",
        "dev.soven.prismllm.shared.domain..",
        "dev.soven.prismllm.pricing.domain..",
    )

    /** infra 可以依赖本模块的领域类型，领域层则不得反向依赖 infra。 */
    private val requestLogInfraDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "org.slf4j..",
        "dev.soven.prismllm.requestlog.domain..",
        "dev.soven.prismllm.requestlog.infra..",
        "dev.soven.prismllm.shared.domain..",
        "dev.soven.prismllm.pricing.domain..",
    )

    /** 日志表与名称解析端口只认识 Exposed、协程原语与本模块，不引入渠道或 HTTP。 */
    private val requestLogPersistenceDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "dev.soven.prismllm.requestlog.domain..",
        "dev.soven.prismllm.requestlog.persistence..",
    )

    /** 请求日志仓储额外依赖计价的只读类型、尝试明细编解码与日志门面。 */
    private val requestLogRepositoryDependencies = arrayOf(
        *requestLogPersistenceDependencies,
        "org.slf4j..",
        "dev.soven.prismllm.shared.domain..",
        "dev.soven.prismllm.pricing.domain..",
        "dev.soven.prismllm.pricing.persistence..",
        "dev.soven.prismllm.requestlog.infra..",
    )

    /** 仓储编译后会拆出多个协程 lambda 类，按全限定名前缀一并识别。 */
    private val requestLogRepositoryPart = DescribedPredicate.describe<JavaClass>(
        "are part of RequestLogRepository",
    ) { clazz -> clazz.name.startsWith("dev.soven.prismllm.requestlog.persistence.RequestLogRepository") }

    private val requestLogPersistenceExceptRepository = DescribedPredicate.describe<JavaClass>(
        "reside in requestlog.persistence but are not part of RequestLogRepository",
    ) { clazz ->
        clazz.packageName.startsWith("dev.soven.prismllm.requestlog.persistence") &&
            !clazz.name.startsWith("dev.soven.prismllm.requestlog.persistence.RequestLogRepository")
    }

    @Test
    fun `import covers the request log module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.domain.RequestLogRecord" },
            "ArchUnit 未导入请求日志模块类，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.infra.UpstreamAttemptCodec" },
            "ArchUnit 未导入尝试明细编解码，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.infra.RequestLogWriter" },
            "ArchUnit 未导入请求日志写入队列，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.infra.LogRetentionService" },
            "ArchUnit 未导入保留清理服务，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.infra.ChannelHealthIndexerKt" },
            "ArchUnit 未导入健康度索引任务，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.infra.ChannelHealthRepository" },
            "ArchUnit 未导入健康度报表仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.domain.ChannelHealthDirectory" },
            "ArchUnit 未导入健康度目录端口，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.domain.HealthChannelOverview" },
            "ArchUnit 未导入健康度概览记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RetentionSettingsRepository" },
            "ArchUnit 未导入保留设置仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RetentionSettings" },
            "ArchUnit 未导入保留设置表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RequestLogs" },
            "ArchUnit 未导入请求日志表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RequestAttempts" },
            "ArchUnit 未导入上游尝试明细表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RequestLogRepository" },
            "ArchUnit 未导入请求日志仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RequestLogNameResolver" },
            "ArchUnit 未导入日志名称解析端口，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.requestlog.persistence.RequestLogNameKind" },
            "ArchUnit 未导入日志名称实体种类，规则会空跑",
        )
    }

    @Test
    fun `request log domain only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.prismllm.requestlog.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*requestLogDomainDependencies)
            .check(classes)
    }

    @Test
    fun `request log infra only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.prismllm.requestlog.infra..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*requestLogInfraDependencies)
            .check(classes)
    }

    @Test
    fun `request log persistence only depends on the allowlist`() {
        classes()
            .that(requestLogPersistenceExceptRepository)
            .should().onlyDependOnClassesThat().resideInAnyPackage(*requestLogPersistenceDependencies)
            .check(classes)
    }

    @Test
    fun `request log repository only depends on the allowlist`() {
        classes()
            .that(requestLogRepositoryPart)
            .should().onlyDependOnClassesThat().resideInAnyPackage(*requestLogRepositoryDependencies)
            .check(classes)
    }
}