package dev.soven.prismllm.admin.domain

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 管理面模块的依赖边界：领域只允许标准库、序列化与共享内核，持久化可再加 Exposed，
 * 插件安装期望由管理模块自行建模，运行时宿主契约只在应用组装层适配。
 *
 * @author 文杰
 * @since 2026-11-10
 */
class AdminArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(
            "dev.soven.prismllm.admin.domain",
            "dev.soven.prismllm.admin.persistence",
        )

    private val adminDomainDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "org.jetbrains.annotations..",
        "dev.soven.prismllm.shared..",
        "dev.soven.prismllm.admin.domain..",
    )

    /** 管理员账号与审计仓储只认识本模块领域模型与 Exposed；suspend 仓储允许协程原语。 */
    private val adminPersistenceDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "org.slf4j..",
        "dev.soven.prismllm.shared..",
        "dev.soven.prismllm.admin.domain..",
        "dev.soven.prismllm.admin.persistence..",
    )

    @Test
    fun `import covers the admin module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.AdminUserRecord" },
            "ArchUnit 未导入管理员账号记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.AdminAuditRecord" },
            "ArchUnit 未导入审计记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.AdminUsers" },
            "ArchUnit 未导入管理员账号表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.AdminAuditLogs" },
            "ArchUnit 未导入审计表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.AdminUserRepository" },
            "ArchUnit 未导入管理员账号仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.AdminAuditRepository" },
            "ArchUnit 未导入审计仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.IqTestRunRecord" },
            "ArchUnit 未导入智商检测记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.NewIqTestRun" },
            "ArchUnit 未导入待写入的智商检测记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.IqTestRuns" },
            "ArchUnit 未导入智商检测表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.IqTestRunRepository" },
            "ArchUnit 未导入智商检测仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.WebSearchSettingsRecord" },
            "ArchUnit 未导入 Web 搜索设置记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.WebSearchSettings" },
            "ArchUnit 未导入 Web 搜索设置表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.WebSearchSettingsRepository" },
            "ArchUnit 未导入 Web 搜索设置仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.NotificationEvent" },
            "ArchUnit 未导入通知事件，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.NotificationRepository" },
            "ArchUnit 未导入通知仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.NotificationDeliveries" },
            "ArchUnit 未导入通知投递表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.PluginInstallationRepository" },
            "ArchUnit 未导入插件安装仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.persistence.PluginInstallations" },
            "ArchUnit 未导入插件安装表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.prismllm.admin.domain.PluginInstallationRecord" },
            "ArchUnit 未导入插件安装领域记录，规则会空跑",
        )
    }

    @Test
    fun `admin domain only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.prismllm.admin.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*adminDomainDependencies)
            .check(classes)
    }

    @Test
    fun `admin persistence only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.prismllm.admin.persistence..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*adminPersistenceDependencies)
            .check(classes)
    }
}
