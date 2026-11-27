package dev.soven.prismllm.api

import dev.soven.prismllm.antigravity.AntigravityAgyAccount
import dev.soven.prismllm.oauth.persistence.AgyProfileRegistration
import dev.soven.prismllm.oauth.domain.OAuthAccountAuthMethod
import dev.soven.prismllm.shared.domain.OAuthProvider
import dev.soven.prismllm.shared.error.badRequest
import dev.soven.prismllm.shared.error.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class AntigravityAgyAccountView(
    val id: String,
    val name: String,
    val sourceAccountId: String? = null,
    val subscriptionTier: String? = null,
    val currentModelId: String? = null,
    val authorizedAt: Long,
    val registered: Boolean,
    val accountId: String? = null,
    val enabled: Boolean = false,
    val routeModels: List<String> = emptyList(),
    val routingEnabled: Boolean = false,
    val cleanupEligible: Boolean = false,
)

@Serializable
data class AntigravityAgyAccountsResponse(val configured: Boolean, val accounts: List<AntigravityAgyAccountView>)

@Serializable
data class AntigravityAgyLoginRequest(val name: String? = null, val sourceAccountId: String? = null, val accountId: String? = null)

@Serializable
data class AntigravityAgyCodeRequest(val code: String)

internal fun Route.antigravityAgyAdminRoutes(app: GatewayApplication) {
    get("/antigravity/agy/accounts") {
        call.requireAdmin(app)
        call.response.headers.append("Cache-Control", "no-store")
        val loginService = app.antigravityAgyLogin
        val profiles = loginService?.list().orEmpty()
        val oauthAccounts = app.oauthAccountRepository.listAll()
        val routes = app.modelCredentialRouteRepository.list()
        val protectedIds = suspendTransaction(app.databaseFactory.database) {
            app.agyProfileProtectedIds(profiles)
        }
        val registered = oauthAccounts
            .filter { it.authMethod == OAuthAccountAuthMethod.AGY }
            .associateBy { it.agyProfileId }
        val registeredById = registered.values.associateBy { it.id }
        val channels = app.channelRepository.list().associateBy { it.id }
        val routedModels = mutableMapOf<String, MutableSet<String>>()
        routes.forEach { route ->
            route.entries.filter { it.credentialType.value == "oauth" }.forEach { entry ->
                val account = registeredById[entry.credentialId] ?: return@forEach
                val channel = channels[entry.channelId] ?: return@forEach
                val hasCompatibleMapping = entry.channelId in account.channelIds && channel.enabled &&
                    channel.modelMappings.any { mapping ->
                        mapping.catalogActive && mapping.publicModelId == route.modelId && account.supports(mapping.upstreamModel)
                    }
                if (hasCompatibleMapping) routedModels.getOrPut(account.id, ::linkedSetOf).add(route.modelId)
            }
        }
        val views = profiles.map { profile ->
            val account = registered[profile.id]
            val models = account?.let { routedModels[it.id].orEmpty().sorted() }.orEmpty()
            AntigravityAgyAccountView(
                id = profile.id,
                name = profile.name,
                sourceAccountId = profile.sourceAccountId,
                subscriptionTier = profile.subscriptionTier,
                currentModelId = profile.currentModelId,
                authorizedAt = profile.authorizedAt,
                registered = account != null,
                accountId = account?.id,
                enabled = account?.enabled == true,
                routeModels = models,
                routingEnabled = account?.enabled == true && account.accountPlan == "PRO" && models.isNotEmpty(),
                cleanupEligible = profile.id !in protectedIds &&
                    profile.sourceAccountId?.let(protectedIds::contains) != true &&
                    loginService?.isProfileInUse(profile.id) != true,
            )
        }
        call.respond(AntigravityAgyAccountsResponse(app.antigravityAgyLogin != null, views))
    }
    delete("/antigravity/agy/accounts/{profileId}") {
        call.requireAdmin(app)
        val service = app.antigravityAgyLogin ?: throw notFound("agy_login_not_found", "官方 CLI 登录未启用")
        service.deleteOrphan(requireNotNull(call.parameters["profileId"])) { profile ->
            transaction(app.databaseFactory.database) {
                val protectedIds = app.agyProfileProtectedIds(listOf(profile))
                profile.id in protectedIds || profile.sourceAccountId?.let(protectedIds::contains) == true
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post("/antigravity/agy/logins") {
        val admin = call.requireAdmin(app)
        val service = app.antigravityAgyLogin
            ?: throw badRequest("agy_not_configured", "官方 agy 登录环境尚未配置，不能回退到旧 OAuth")
        val request = call.receive<AntigravityAgyLoginRequest>()
        val source = request.sourceAccountId?.let { id ->
            val account = app.oauthAccountRepository.findDetailed(id)
                ?: throw notFound("oauth_account_not_found", "待迁移的旧订阅账号不存在")
            if (account.provider != OAuthProvider.ANTIGRAVITY) {
                throw badRequest("agy_invalid_source", "只能关联 Antigravity 旧账号")
            }
            account
        }
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(HttpStatusCode.Accepted, service.start(admin.username, request.name ?: source?.name, source?.id, request.accountId))
    }
    get("/antigravity/agy/logins/{loginId}") {
        val admin = call.requireAdmin(app)
        val service = app.antigravityAgyLogin ?: throw notFound("agy_login_not_found", "官方 CLI 登录未启用")
        call.response.headers.append("Cache-Control", "no-store")
        val login = service.get(requireNotNull(call.parameters["loginId"]), admin.username)
        call.respond(login)
    }
    post("/antigravity/agy/logins/{loginId}/code") {
        val admin = call.requireAdmin(app)
        val service = app.antigravityAgyLogin ?: throw notFound("agy_login_not_found", "官方 CLI 登录未启用")
        val request = call.receive<AntigravityAgyCodeRequest>()
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(HttpStatusCode.Accepted,
            service.submit(requireNotNull(call.parameters["loginId"]), admin.username, request.code))
    }
    delete("/antigravity/agy/logins/{loginId}") {
        val admin = call.requireAdmin(app)
        val service = app.antigravityAgyLogin ?: throw notFound("agy_login_not_found", "官方 CLI 登录未启用")
        service.stop(requireNotNull(call.parameters["loginId"]), admin.username)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun GatewayApplication.agyProfileProtectedIds(profiles: List<AntigravityAgyAccount>): Set<String> {
    val candidateIds = profiles.flatMap { listOfNotNull(it.id, it.sourceAccountId) }.toSet()
    return oauthAccountRepository.referencedAgyProfileIdsInTransaction(candidateIds) +
        modelCredentialRouteRepository.referencedCredentialIdsInTransaction(candidateIds)
}

internal suspend fun GatewayApplication.registerAgyProfile(profile: AntigravityAgyAccount) {
    if (profile.subscriptionTier != "PRO") return
    val account = oauthAccountRepository.registerAgyProfile(
        AgyProfileRegistration(
            profileId = profile.id,
            sourceAccountId = profile.sourceAccountId,
            name = profile.name,
            subscriptionTier = profile.subscriptionTier,
            currentModelId = profile.currentModelId,
        ),
    )
    if (account.providerChannelPending) {
        providerChannelProvisioner.attachAccount(account.id)
    } else {
        providerChannelProvisioner.syncModelUnion(OAuthProvider.ANTIGRAVITY)
    }
}
