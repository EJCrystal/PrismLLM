package dev.soven.llmgateway.admin.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 管理面模块的依赖边界：领域只允许标准库、序列化与共享内核，持久化可再加 Exposed，
 * 禁止反向引用渠道、路由或应用层。
 *
 * @author 文杰
 * @since 2026-11-10
 */
class AdminArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(
            "dev.soven.llmgateway.admin.domain",
            "dev.soven.llmgateway.admin.persistence",
        )

    private val adminDomainDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "org.jetbrains.annotations..",
        "dev.soven.llmgateway.shared..",
    )

    /** 管理员账号与审计仓储只认识本模块领域模型与 Exposed；suspend 仓储允许协程原语。 */
    private val adminPersistenceDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "dev.soven.llmgateway.shared..",
        "dev.soven.llmgateway.admin.domain..",
        "dev.soven.llmgateway.admin.persistence..",
    )

    @Test
    fun `import covers the admin module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.domain.AdminUserRecord" },
            "ArchUnit 未导入管理员账号记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.domain.AdminAuditRecord" },
            "ArchUnit 未导入审计记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.persistence.AdminUsers" },
            "ArchUnit 未导入管理员账号表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.persistence.AdminAuditLogs" },
            "ArchUnit 未导入审计表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.persistence.AdminUserRepository" },
            "ArchUnit 未导入管理员账号仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.persistence.AdminAuditRepository" },
            "ArchUnit 未导入审计仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.domain.IqTestRunRecord" },
            "ArchUnit 未导入智商检测记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.domain.NewIqTestRun" },
            "ArchUnit 未导入待写入的智商检测记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.persistence.IqTestRuns" },
            "ArchUnit 未导入智商检测表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.admin.persistence.IqTestRunRepository" },
            "ArchUnit 未导入智商检测仓储，规则会空跑",
        )
    }

    @Test
    fun `admin domain only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.admin.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*adminDomainDependencies)
            .check(classes)
    }

    @Test
    fun `admin persistence only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.admin.persistence..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*adminPersistenceDependencies)
            .check(classes)
    }
}
