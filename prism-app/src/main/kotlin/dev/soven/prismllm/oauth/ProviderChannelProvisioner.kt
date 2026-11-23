package dev.soven.prismllm.oauth

import dev.soven.prismllm.channel.domain.ChannelModelRecord
import dev.soven.prismllm.channel.domain.ChannelRecord
import dev.soven.prismllm.channel.domain.ProviderEndpoints
import dev.soven.prismllm.channel.persistence.ChannelRepository
import dev.soven.prismllm.oauth.domain.OAuthAccountModelCatalogEntry
import dev.soven.prismllm.oauth.domain.OAuthAccountRecord
import dev.soven.prismllm.oauth.persistence.OAuthAccountRepository
import dev.soven.prismllm.shared.domain.OAuthProvider
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** 仅包含管理员本次修改的行，不替换整个 provider 目录。 */
data class ProviderModelMappingChange(
    val upstreamModel: String,
    val mode: String,
    val canonicalModelId: String? = null,
)

data class ProviderModelMapping(
    val mapping: ChannelModelRecord,
    val automaticCanonicalModelId: String?,
) {
    val mode: String get() = when {
        mapping.mappingSource == null -> "auto"
        mapping.canonicalModelId == null -> "upstream"
        else -> "manual"
    }
}

data class ProviderModelMappings(
    val provider: OAuthProvider,
    val revision: Long,
    val affectedAccountCount: Int,
    val mappings: List<ProviderModelMapping>,
)

class ModelMappingRevisionChangedException : RuntimeException("共享模型映射已变化，请刷新后再保存")

/**
 * 每个 provider 共用模型映射，账号目录与授权仍然独立。
 * 所有共享写入在一个显式事务内先锁 channel 父行、再锁 account；不依赖进程 Mutex，
 * 也不假设不同仓储的嵌套 suspendTransaction 能提供原子性。
 */
class ProviderChannelProvisioner(
    private val channels: ChannelRepository,
    private val accounts: OAuthAccountRepository,
) {
    private val logger = LoggerFactory.getLogger(ProviderChannelProvisioner::class.java)

    suspend fun ensure(provider: OAuthProvider): String? = channels.inTransaction {
        channels.ensureProviderChannelInTransaction(provider)?.id
    }

    /** 只读，不置备、不抓取上游；父行锁保证 revision 与默认目录来自同一快照。 */
    suspend fun modelMappings(provider: OAuthProvider): ProviderModelMappings = channels.inTransaction {
        val channel = channels.findProviderChannelInTransaction(provider, lock = true)
        val providerAccounts = accounts.listProviderInTransaction(provider)
        buildModelMappings(provider, channel, providerAccounts)
    }

    /** 提供商级共享模型映射保存；事务内先锁 provider 渠道父行校验版本再写入。 */
    suspend fun updateModelMappings(
        provider: OAuthProvider,
        changes: List<ProviderModelMappingChange>,
        expectedRevision: Long,
    ): ProviderModelMappings {
        val validated = validateChanges(changes)
        require(expectedRevision >= 0) { "映射版本不能为负数" }
        return channels.inTransaction {
            val channel = channels.ensureProviderChannelInTransaction(provider)
                ?: throw IllegalArgumentException("该提供方的上游协议尚未实现")
            val providerAccounts = accounts.listProviderInTransaction(provider)
            val saved = if (validated.isEmpty()) {
                if (expectedRevision != channel.modelMappingRevision) throw ModelMappingRevisionChangedException()
                channel
            } else {
                applyModelMappingChangesInTransaction(
                    channel = channel,
                    providerAccounts = providerAccounts,
                    changes = validated,
                    expectedRevision = expectedRevision,
                    allowCreate = true,
                )
            }
            buildModelMappings(provider, saved, providerAccounts)
        }
    }

    /**
     * 切换订阅提供商系统端点渠道的启停总开关。
     * 只操作已置备的渠道，不会被一次开关请求顺带创建空渠道。
     */
    suspend fun setProviderEnabled(provider: OAuthProvider, enabled: Boolean): ChannelRecord? = channels.inTransaction {
        val channel = channels.findProviderChannelInTransaction(provider, lock = true) ?: return@inTransaction null
        channels.setEnabledInTransaction(channel.id, enabled)
    }

    suspend fun updateSettings(
        accountId: String,
        name: String? = null,
        priority: Int? = null,
        enabled: Boolean? = null,
        proxyUrl: String? = null,
        clearProxy: Boolean = false,
        models: List<String>? = null,
        modelMappingChanges: List<ProviderModelMappingChange>? = null,
        expectedModelMappingRevision: Long? = null,
    ): OAuthAccountRecord? {
        val changes = validateChanges(modelMappingChanges.orEmpty())
        val normalizedModels = models?.let(::validateModels)
        require(priority == null || priority in 1..1000) { "账号优先级必须在 1..1000 之间" }
        require(!(clearProxy && proxyUrl != null)) { "不能同时更换和清除账号代理" }
        require(expectedModelMappingRevision == null || expectedModelMappingRevision >= 0) { "映射版本不能为负数" }
        require(changes.isEmpty() || expectedModelMappingRevision != null) { "修改共享模型映射必须提供版本" }
        return channels.inTransaction {
            val existing = accounts.findDetailedInTransaction(accountId) ?: return@inTransaction null
            val channel = channels.ensureProviderChannelInTransaction(existing.provider)
            val current = accounts.findDetailedInTransaction(accountId, lock = true) ?: return@inTransaction null
            if (changes.isNotEmpty()) {
                requireNotNull(channel) { "该提供方的上游协议尚未实现" }
                applyModelMappingChangesInTransaction(
                    channel = channel,
                    providerAccounts = accounts.listProviderInTransaction(current.provider),
                    changes = changes,
                    expectedRevision = requireNotNull(expectedModelMappingRevision),
                )
            }
            if (normalizedModels != null) accounts.setModelsInTransaction(accountId, normalizedModels)
            if (name != null || priority != null || enabled != null || proxyUrl != null || clearProxy) {
                accounts.updateSettingsInTransaction(accountId, name, priority, enabled, proxyUrl, clearProxy)
            }
            accounts.findDetailedInTransaction(accountId)
        }
    }

    private fun buildModelMappings(
        provider: OAuthProvider,
        channel: ChannelRecord?,
        providerAccounts: List<OAuthAccountRecord>,
    ): ProviderModelMappings {
        val defaults = automaticDefaults(providerAccounts)
        return ProviderModelMappings(
            provider = provider,
            revision = channel?.modelMappingRevision ?: 0,
            affectedAccountCount = providerAccounts.size,
            mappings = channel?.modelMappings.orEmpty().map { ProviderModelMapping(it, defaults[it.upstreamModel]) },
        )
    }

    private fun applyModelMappingChangesInTransaction(
        channel: ChannelRecord,
        providerAccounts: List<OAuthAccountRecord>,
        changes: List<ProviderModelMappingChange>,
        expectedRevision: Long,
        allowCreate: Boolean = false,
    ): ChannelRecord {
        if (expectedRevision != channel.modelMappingRevision) throw ModelMappingRevisionChangedException()
        val defaults = automaticDefaults(providerAccounts)
        val byUpstream = channel.modelMappings.associateBy { it.upstreamModel }.toMutableMap()
        changes.forEach { change ->
            val previous = byUpstream[change.upstreamModel]
            if (previous == null) {
                require(allowCreate) { "共享目录不存在模型: ${change.upstreamModel}" }
                // 人工新增共享行：先以休眠手工映射保存，等账号目录出现该模型时再由并集自动激活。
                require(change.mode != "auto") { "新增共享映射不能使用自动模式: ${change.upstreamModel}" }
                byUpstream[change.upstreamModel] = ChannelModelRecord(
                    upstreamModel = change.upstreamModel,
                    canonicalModelId = if (change.mode == "manual") change.canonicalModelId else null,
                    mappingSource = "manual",
                    catalogActive = false,
                )
                return@forEach
            }
            byUpstream[change.upstreamModel] = previous.copy(
                canonicalModelId = when (change.mode) {
                    "manual" -> change.canonicalModelId
                    "upstream" -> null
                    else -> defaults[change.upstreamModel]
                },
                mappingSource = if (change.mode == "auto") null else "manual",
            )
        }
        return channels.saveModelMappingsInTransaction(channel.id, byUpstream.values.sortedBy { it.upstreamModel })
    }

    /** 上游抓取在调用方完成；目录、当前授权裁剪和共享并集同一事务提交。 */
    suspend fun syncModelCatalog(
        accountId: String,
        catalog: List<OAuthAccountModelCatalogEntry>,
    ): Boolean = channels.inTransaction {
        val existing = accounts.findDetailedInTransaction(accountId) ?: return@inTransaction false
        val channel = channels.ensureProviderChannelInTransaction(existing.provider) ?: return@inTransaction false
        val current = accounts.findDetailedInTransaction(accountId, lock = true) ?: return@inTransaction false
        val oldDefaults = automaticDefaults(accounts.listProviderInTransaction(current.provider))
        if (!accounts.saveModelCatalogInTransaction(accountId, catalog)) return@inTransaction false
        val saved = requireNotNull(accounts.findDetailedInTransaction(accountId))
        // 无条件读当前授权；空列表继续表示全部，显式授权落空仍保留原有哨兵语义。
        accounts.removeModelsOutsideCatalogInTransaction(accountId, saved.modelCatalog.orEmpty().map { it.upstreamModel })
        val providerAccounts = accounts.listProviderInTransaction(current.provider)
        saveUnion(channel, providerAccounts, oldDefaults)
        true
    }

    suspend fun syncModelUnion(provider: OAuthProvider): Int = channels.inTransaction {
        val channel = channels.ensureProviderChannelInTransaction(provider) ?: return@inTransaction 0
        saveUnion(channel, accounts.listProviderInTransaction(provider)).models.size
    }

    /** 旧 provider/models 写入仍可维护目录，但不能抹掉人工覆盖或复活休眠行。 */
    suspend fun setProviderModels(provider: OAuthProvider, models: List<String>): Boolean {
        val normalized = validateModels(models)
        return channels.inTransaction {
            val channel = channels.ensureProviderChannelInTransaction(provider) ?: return@inTransaction false
            val defaults = automaticDefaults(accounts.listProviderInTransaction(provider))
            val existing = channel.modelMappings.associateBy { it.upstreamModel }
            val desired = normalized.map { model ->
                existing[model]
                    ?: ChannelModelRecord(model, defaults[model])
            } + channel.modelMappings.filter { it.upstreamModel !in normalized && it.mappingSource != null }
                .map { it.copy(catalogActive = false) }
            channels.saveModelMappingsInTransaction(channel.id, desired)
            true
        }
    }

    suspend fun deleteAccount(accountId: String): Boolean = channels.inTransaction {
        val existing = accounts.findDetailedInTransaction(accountId) ?: return@inTransaction false
        val channel = channels.findProviderChannelInTransaction(existing.provider, lock = true)
        val current = accounts.findDetailedInTransaction(accountId, lock = true) ?: return@inTransaction false
        val oldDefaults = automaticDefaults(accounts.listProviderInTransaction(current.provider))
        if (!accounts.deleteInTransaction(accountId)) return@inTransaction false
        if (channel != null) {
            val remaining = accounts.listProviderInTransaction(current.provider)
            saveUnion(channel, remaining, oldDefaults)
        }
        true
    }

    suspend fun attachAccount(accountId: String): OAuthAccountRecord? = channels.inTransaction {
        val existing = accounts.findDetailedInTransaction(accountId) ?: return@inTransaction null
        val channel = channels.ensureProviderChannelInTransaction(existing.provider) ?: return@inTransaction existing
        val current = accounts.findDetailedInTransaction(accountId, lock = true) ?: return@inTransaction null
        if (current.channelIds != setOf(channel.id)) accounts.setChannelsInTransaction(accountId, listOf(channel.id))
        accounts.markProviderChannelSettledInTransaction(accountId)
        saveUnion(channel, accounts.listProviderInTransaction(current.provider))
        accounts.findDetailedInTransaction(accountId)
    }

    /** 启动搬迁与并集对账可重试，已就位的账号不改基础配置。 */
    suspend fun reconcile(): Int = reconcile(strict = false)

    /** V74 首次 seed 前必须完成搬迁；失败要中止迁移，不能留下旧渠道身份。 */
    suspend fun reconcileBeforeModelCredentialRouteMigration(): Int = reconcile(strict = true)

    private suspend fun reconcile(strict: Boolean): Int {
        var moved = 0
        val servable = OAuthProvider.entries.filter { ProviderEndpoints.of(it).servable }
        do {
            val pending = accounts.listPendingProviderChannel(servable)
            for (account in pending) {
                try {
                    val attached = attachAccount(account.id) ?: continue
                    if (attached.channelIds != account.channelIds) moved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("failed to move subscription account onto provider channel, accountId={}", account.id, e)
                    if (strict) throw IllegalStateException(
                        "Unable to reconcile pending subscription account ${account.id} before V74 migration",
                        e,
                    )
                }
            }
            // Ordinary startup retries one bounded batch; migration must settle every batch first.
        } while (strict && pending.isNotEmpty())
        for (provider in servable) {
            channels.inTransaction {
                val existing = channels.findProviderChannelInTransaction(provider, lock = true)
                val providerAccounts = accounts.listProviderInTransaction(provider)
                if (existing != null || providerAccounts.isNotEmpty()) {
                    val channel = existing ?: channels.ensureProviderChannelInTransaction(provider) ?: return@inTransaction
                    saveUnion(channel, providerAccounts)
                }
            }
        }
        return moved
    }

    private fun saveUnion(
        channel: ChannelRecord,
        providerAccounts: List<OAuthAccountRecord>,
        previousDefaults: Map<String, String?>? = null,
    ): ChannelRecord {
        val defaults = automaticDefaults(providerAccounts)
        val active = defaults.keys.toMutableSet()
        if (providerAccounts.any { it.modelCatalog == null }) {
            // 未知目录仅保留旧 active，不把人工休眠行当作未知账号的可调用模型。
            active += channel.models
        }
        val previous = channel.modelMappings.associateBy { it.upstreamModel }
        val desired = active.map { model ->
            val old = previous[model]
            when {
                old?.mappingSource != null -> old.copy(catalogActive = true)
                // unknown 只保留 active 集合；auto 仍服从当前默认，无默认时沿用上游名。
                else -> ChannelModelRecord(model, defaults[model])
            }
        } + channel.modelMappings.filter { it.upstreamModel !in active && it.mappingSource != null }
            .map { it.copy(catalogActive = false) }
        // 缺失默认与 null 都显示为沿用上游；只比较旧共享行的有效默认（含人工覆盖）。
        // 目录成员与 active 变化由仓储的 mappingsChanged 判定，不能因 Map 键变化额外推进版本。
        val defaultsChanged = previousDefaults != null && channel.modelMappings.any {
            previousDefaults[it.upstreamModel] != defaults[it.upstreamModel]
        }
        // 全部已知且并集为空也提交：auto 删除，manual 休眠保留。
        return channels.saveModelMappingsInTransaction(channel.id, desired.sortedBy { it.upstreamModel }, defaultsChanged)
    }

    private fun automaticDefaults(providerAccounts: List<OAuthAccountRecord>): Map<String, String?> {
        val defaults = linkedMapOf<String, String?>()
        providerAccounts.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { account ->
            account.modelCatalog.orEmpty().forEach { entry ->
                // null 是有效默认（自动沿用上游），不能用 putIfAbsent 的 null 覆盖语义。
                if (entry.upstreamModel !in defaults) defaults[entry.upstreamModel] = entry.canonicalModelId
            }
        }
        return defaults
    }

    private fun validateChanges(changes: List<ProviderModelMappingChange>): List<ProviderModelMappingChange> {
        val normalized = changes.map { change ->
            val upstream = change.upstreamModel.trim()
            require(upstream.length in 1..200) { "模型名长度必须在 1..200 之间" }
            require(change.mode in setOf("auto", "manual", "upstream")) { "映射模式无效" }
            val canonical = change.canonicalModelId?.trim()
            if (change.mode == "manual") {
                require(canonical != null && canonical.length in 1..200) { "人工模型 ID 长度必须在 1..200 之间" }
            } else {
                require(change.canonicalModelId == null) { "auto/upstream 模式不能指定 canonicalModelId" }
            }
            change.copy(upstreamModel = upstream, canonicalModelId = canonical)
        }
        require(normalized.map { it.upstreamModel }.distinct().size == normalized.size) { "不能重复提交同一上游模型" }
        return normalized
    }

    private fun validateModels(models: List<String>): List<String> = models.map(String::trim).filter(String::isNotEmpty)
        .distinct().also { values -> require(values.all { it.length <= 200 }) { "模型名长度不能超过 200" } }
}
