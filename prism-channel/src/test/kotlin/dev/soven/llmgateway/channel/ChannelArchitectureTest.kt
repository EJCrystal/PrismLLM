package dev.soven.llmgateway.channel

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * 渠道模块的依赖边界：只允许共享内核、持久化、序列化、协程原语、日志门面、
 * 额度插件 SPI 与缓存工具，禁止反向引用应用层。
 *
 * 渠道被路由、代理与管理面依赖，一旦它反过来引用 proxy/api 就会重新把模块拆成环。
 *
 * @author 文杰
 * @since 2026-11-09
 */
class ChannelArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.soven.llmgateway.channel")

    private val channelDependencies = arrayOf(
        "java..",
        "kotlin..",
        "kotlinx.serialization..",
        "kotlinx.coroutines..",
        "org.jetbrains.annotations..",
        "org.jetbrains.exposed..",
        "org.slf4j..",
        "com.google.common..",
        "com.google.re2j..",
        "dev.soven.llmgateway.shared..",
        "dev.soven.llmgateway.spi..",
        "dev.soven.llmgateway.channel..",
    )

    @Test
    fun `import covers the channel module`() {
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.domain.ChannelRecord" },
            "ArchUnit 未导入渠道模块类，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.domain.key.ChannelKeyRateLimiter" },
            "ArchUnit 未导入密钥限流器，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.domain.UpstreamHeaderProfile" },
            "ArchUnit 未导入上游请求头档案，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.domain.UpstreamHeaderCatalogSnapshot" },
            "ArchUnit 未导入请求头目录快照，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.domain.UpstreamRequestSettingsRecord" },
            "ArchUnit 未导入上游请求覆盖设置，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.persistence.KeyAutoDisableRepository" },
            "ArchUnit 未导入密钥自动停用仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.infra.quota.ChannelQuotaService" },
            "ArchUnit 未导入渠道额度查询服务，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.infra.quota.ChannelQuotaProviderRegistry" },
            "ArchUnit 未导入额度插件注册表，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.persistence.UpstreamRequestSettingsRepository" },
            "ArchUnit 未导入上游请求设置仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.persistence.UpstreamHeaderCatalogRepository" },
            "ArchUnit 未导入请求头目录仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.domain.ModelTestSettingsRecord" },
            "ArchUnit 未导入模型测试设置记录，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.persistence.ModelTestSettingsRepository" },
            "ArchUnit 未导入模型测试设置仓储，规则会空跑",
        )
        assertTrue(
            classes.any { it.name == "dev.soven.llmgateway.channel.persistence.ModelTestSettings" },
            "ArchUnit 未导入模型测试设置表，规则会空跑",
        )
    }

    @Test
    fun `channel module only depends on the allowlist`() {
        classes()
            .that().resideInAnyPackage("dev.soven.llmgateway.channel..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(*channelDependencies)
            .check(classes)
    }

    @Test
    fun `channel subpackages are free of cycles`() {
        slices().matching("dev.soven.llmgateway.channel.(*)..")
            .should().beFreeOfCycles()
            .check(classes)
    }
}
