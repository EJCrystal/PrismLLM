# 调用方密钥访问范围 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理员可以为调用方密钥配置渠道、静态渠道密钥及对外模型 ID 白名单，数据面严格在授权范围内选路。

**Architecture:** 调用方密钥模块保存不依赖渠道类型的访问范围，应用层校验与投影到路由模块的只读参数。路由目录在同一配置快照上计算授权与可用性，沿用现有候选调度；管理界面编辑完整配置对象。

**Tech Stack:** Kotlin、Java 21、Ktor、Exposed、PostgreSQL、Flyway、Vue 3、TypeScript、Node 测试、Maven。

**Spec:** `docs/superpowers/specs/2026-11-14-client-key-access-scope-design.md`

## Global Constraints

- 同维度取并集，不同维度取交集。模型 ID 精确匹配，不支持通配符，也不改写大小写。
- 白名单为空表示该维度不允许任何对象；不限制与空白名单必须区分。
- 渠道密钥使用 `(channelId, keyId)`；静态密钥白名单排除 OAuth 候选。
- 授权过滤先于上游许可、探测和调用，普通重试及冷却兜底均不得越界。
- 三个维度均不限制时保留现有路由和错误语义，包括仅停用渠道中的模型返回 404。
- 更新省略访问范围或传 null 时保留原范围；清除限制显式提交不限制模式。
- 模式与关联记录原子写入、同一数据库快照读取；轮换保留授权，目标删除不放开授权。
- 只改现有 GET `/v1/models` 和 `/v1beta/models`；覆盖两个版本的 Gemini 生成请求路径。
- 维持现有 ArchUnit 依赖白名单；不引入依赖、不升级版本；新增 Kotlin 代码提供标准 KDoc。
- 使用独立 `feat/client-key-access-scope` 工作树；不修改原工作区或混入 OAuth 界面分支。
- 后端使用 WSL 原生测试镜像与缓存；不读取生产 `.env`，不连接生产数据库。

## Review Focus

- 状态切换提交旧 payload 时不得清空授权：任务 1 API 回归与任务 3 组件回归。
- 删除唯一授权渠道/密钥后不得转为不限制：任务 1 持久化删除回归与任务 2 路由回归。
- 权限外候选健康但权限内候选不可用时不得借冷却兜底越界：任务 2 集成回归。
- 相同对外模型 ID 映射到不同上游模型名时授权仍按对外 ID：任务 2 目录与请求回归。
- 编辑表单切换白名单/不限制、多选及请求失败时不得丢失已保存范围：任务 3 真实组件回归。

---

### Task 1: 调用方密钥访问范围持久化与管理 API

**Files:**
- Create: `prism-quota/src/main/kotlin/dev/soven/prismllm/quota/domain/ClientKeyAccessPolicy.kt`
- Modify: `prism-quota/src/main/kotlin/dev/soven/prismllm/quota/domain/ClientKeyRecord.kt`
- Create: `prism-quota/src/main/kotlin/dev/soven/prismllm/quota/persistence/ClientKeyAccessTables.kt`
- Modify: `prism-quota/src/main/kotlin/dev/soven/prismllm/quota/persistence/ClientKeyTables.kt`
- Modify: `prism-quota/src/main/kotlin/dev/soven/prismllm/quota/persistence/ClientKeyRepository.kt`
- Create: `prism-app/src/main/resources/db/migration/V68__client_key_access_scope.sql`
- Create: `prism-app/src/main/kotlin/dev/soven/prismllm/api/ClientKeyAccessDtos.kt`
- Create: `prism-app/src/main/kotlin/dev/soven/prismllm/api/ClientKeyAccessPolicies.kt`
- Modify: `prism-app/src/main/kotlin/dev/soven/prismllm/api/AdminDtos.kt`
- Modify: `prism-app/src/main/kotlin/dev/soven/prismllm/api/AdminRoutes.kt`
- Test: `prism-quota/src/test/kotlin/dev/soven/prismllm/quota/domain/ClientKeyAccessPolicyTest.kt`
- Test: `prism-app/src/test/kotlin/dev/soven/prismllm/integration/ClientKeyAccessPolicyIntegrationTest.kt`

**Interfaces:**
- Produces: `data class ClientKeyAllowedChannelKey(val channelId: String, val keyId: String)`.
- Produces: `data class ClientKeyAccessPolicy(val allowedChannelIds: Set<String>? = null, val allowedChannelKeys: Set<ClientKeyAllowedChannelKey>? = null, val allowedModelIds: Set<String>? = null)`; `isUnrestricted: Boolean` is true only when all sets are null.
- Produces: appended `ClientKeyRecord.accessPolicy: ClientKeyAccessPolicy = ClientKeyAccessPolicy()`.
- Produces: appended create parameter `accessPolicy: ClientKeyAccessPolicy = ClientKeyAccessPolicy()` and update parameter `accessPolicy: ClientKeyAccessPolicy? = null`; existing callers remain valid.
- Produces: JSON `accessPolicy` with required `channels`, `channelKeys`, `models` objects. Each has required `mode: "unrestricted" | "allowlist"`; ID scopes use `ids: string[]`, key scope uses `keys: {channelId,keyId}[]`. Unrestricted modes have empty arrays. Read responses always include all scopes.
- Consumes: existing `GatewayIntegrationTestBase`, real PostgreSQL container, admin authentication and audit patterns.

- [ ] **Step 1: Write failing tests.** Assert null versus empty policy, create/read/save round trip, omitted/null update preservation, explicit clear, rotate preservation, invalid key/channel pair rejection, removal of target retaining restricted mode, unknown model rejection, and unrelated quota/status updates preserving policy. Use real repository/API behavior.
- [ ] **Step 2: Run RED.** Use the supplied WSL runner with `-pl prism-app -am -Dtest=ClientKeyAccessPolicyTest,ClientKeyAccessPolicyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`; record expected missing policy/response behavior.
- [ ] **Step 3: Implement domain, schema and repository.** Persist three restriction flags and three allowlist tables. Migration uses SQL foreign keys to existing channel/key tables without importing their classes into quota. IDs are normalized; writes are atomic; hash lookup, find and list read a consistent snapshot, with batch association reads. Use cascades that remove associations while retaining restriction flags. Preserve existing lifecycle behavior and quota settlement transactions.
- [ ] **Step 4: Implement DTO mapping and validation.** Keep new DTOs and validators in focused application files. Validate all channels, static key ownership, configured model IDs including temporarily disabled objects, and channel/key intersections. Explicit accessPolicy replaces all scopes; absent/null preserves on update. Include canonical policy in both read response mappings. Existing audit path applies to updates.
- [ ] **Step 5: Run GREEN and task regression.** Run targeted tests and quota ArchUnit test; run the app tests relevant to client-key lifecycle and existing admin requests. Record commands and results; no broad frontend change.
- [ ] **Step 6: Commit only task files.** Subject: `feat(auth): persist client key access scopes`.

### Task 2: 路由授权、诊断和模型列表

**Files:**
- Create: `prism-routing/src/main/kotlin/dev/soven/prismllm/routing/domain/candidate/RoutingAccessScope.kt`
- Modify: `prism-routing/src/main/kotlin/dev/soven/prismllm/routing/domain/candidate/CandidateSet.kt`
- Modify: `prism-routing/src/main/kotlin/dev/soven/prismllm/routing/readmodel/UpstreamModelCatalog.kt`
- Create: `prism-app/src/main/kotlin/dev/soven/prismllm/api/ClientKeyRoutingAccess.kt`
- Modify: `prism-app/src/main/kotlin/dev/soven/prismllm/api/ProxyRoutes.kt`
- Modify: `prism-app/src/main/kotlin/dev/soven/prismllm/api/ExposedModels.kt`
- Test: `prism-routing/src/test/kotlin/dev/soven/prismllm/routing/domain/candidate/RoutingAccessScopeTest.kt`
- Test: `prism-app/src/test/kotlin/dev/soven/prismllm/integration/ClientKeyRoutingAccessIntegrationTest.kt`
- Test: focused `UpstreamModelCatalog` tests where existing directory test patterns allow.

**Interfaces:**
- Consumes: task 1 `ClientKeyAccessPolicy` and its nullable ID sets.
- Produces: routing-local `RoutingAccessScope` with equivalent nullable sets and routing-local key reference; application maps into it, routing never imports quota.
- Produces: `CandidateSet.accessDenied: Boolean = false` appended to existing fields.
- Produces: `UpstreamModelCatalog.resolve(model, protocol, now = ..., scope: RoutingAccessScope = RoutingAccessScope())` and `listDirectModels(scope: RoutingAccessScope = RoutingAccessScope())`; existing callers preserve results.
- Produces: `listExposedModelIds(app, protocol = null, scope: RoutingAccessScope = RoutingAccessScope())`.
- Produces: 403 codes `model_access_denied` for a disallowed requested public model and `route_access_denied` for channel/key access denial, with zero upstream attempts.

- [ ] **Step 1: Write failing behavior tests.** Exercise allowed/denied model and key, same public model on multiple channels, intersection, empty lists, model lists, temporary/permanent key disable, disabled channel, no eligible protocol, OAuth exclusion, Gemini URL variants, search requests, normal failover and all-cooling fallback. Assert fake upstream counters and released client concurrency on denial.
- [ ] **Step 2: Run RED.** Run focused directory/routing integration tests through WSL before changing routing behavior; expected old behavior wrongly exposes or routes outside scope.
- [ ] **Step 3: Implement scoped catalog resolution.** Use one full configuration snapshot. For unrestricted scopes retain existing enabled-channel behavior exactly. For restricted scopes first distinguish configured models and authorized configured credentials (including disabled targets), then calculate reachable protocols, currently routable candidates and retryAt only within scope. Use `UpstreamCredential.isOAuth` to reject OAuth in static-key allowlist mode. An allowed model with no global mapping remains 404; known mapping with no authorized channel/key sets accessDenied; authorized but unavailable yields existing unavailable diagnostics. Do not duplicate the original candidate ordering.
- [ ] **Step 4: Wire request and list endpoints.** Map policy in a focused application adapter. Check model allowlist after parsing model and before upstream preparation; deny catalog accessDenied before candidate loops. Both normal and fallback loops consume the same filtered candidates. Authenticated model listings receive scope; preserve response formats and existing native-protocol listing behavior. Search's internal calls reuse the authorized candidate.
- [ ] **Step 5: Run GREEN and regression.** Run new tests plus routing ArchUnit, `RoutingFailoverIntegrationTest`, `RateLimitIntegrationTest`, `ChannelKeyPoolIntegrationTest`, `ProtocolConversionIntegrationTest`, `RequestFinalizationIntegrationTest`, `GracefulShutdownIntegrationTest`; verify unrestricted legacy disabled-channel 404 and post-commit stream behavior.
- [ ] **Step 6: Commit task files.** Subject: `feat(routing): enforce client key access scopes`.

### Task 3: 调用方密钥管理界面、文档和完整验证

**Files:**
- Modify: `web/src/types/api.ts`
- Create: `web/src/utils/clientKeyAccess.ts`
- Create: `web/src/components/ClientKeyAccessFields.vue`
- Modify: `web/src/components/ClientKeysView.vue`
- Modify: `web/src/utils/clientKeyQuota.ts` if payload construction needs policy support.
- Test: `web/test/clientKeyAccess.test.js`
- Test: `web/test/clientKeyAccessFields.test.js`
- Test: `web/test/clientKeysAccessView.test.js`
- Modify: `CONTEXT.md`, `README.md`, `docs/routing-and-resilience.md` for delivered behavior.

**Interfaces:**
- Consumes: task 1 exact API accessPolicy JSON, existing channel/static-key and configured-model management APIs, task 2 scoped route/list behavior.
- Produces: TypeScript accessPolicy types matching JSON; helper functions to create an unrestricted policy, normalize form/payload and display a short access summary.
- Produces: real reusable `ClientKeyAccessFields.vue` with three explicit mode selectors and multi-selection, wired to `ClientKeysView` create/edit/save.

- [ ] **Step 1: Write failing tests.** Mount real Vue components in jsdom using existing Vite/testing patterns. Verify mode switching, multiple IDs, static key/channel pairing, edit round trip, empty restricted mode, request failure preserving selection and enabled toggle preserving saved policy. Unit tests call actual helpers, never duplicate helper expressions.
- [ ] **Step 2: Run RED.** `node --import tsx --test web/test/clientKeyAccess*.test.js web/test/clientKeysAccessView.test.js` (use existing project test loader as needed); expected missing UI/payload behavior.
- [ ] **Step 3: Implement UI and types.** Load management catalog data only where needed for the form. Use accessible controls consistent with existing components. Keep unavailable/removed selection state explicit; do not silently reset or widen authorization. No plaintext upstream credentials in options. Keep existing key actions intact.
- [ ] **Step 4: Run GREEN and frontend regression.** `npm --prefix web test`, `npm --prefix web run typecheck`, `npm --prefix web run build`. Use browser/fixture inspection if available to verify the new form layout; fix any overflow.
- [ ] **Step 5: Document behavior.** Add access-scope terminology to existing CONTEXT Language section and document mode semantics, key rotation, endpoint filtering and strict failover. Avoid creating duplicate glossary or unrelated ADR.
- [ ] **Step 6: Commit task files.** Subject: `feat(web): configure client key access scopes`.
- [ ] **Step 7: Full branch verification.** Run full main-engine `mvn test`, `mvn -DskipTests package`, frontend checks and `git diff --check`. Report skipped browser tests separately. Resolve review findings and preserve branch for review; no push or PR without user direction.

## Execution notes

User explicitly authorized implementation; proceed through all tasks without another method/plan approval prompt.
Use serial task implementers and independent task reviews. Controller owns the plan, ledger, runner and final verification.
Previously verified main backend baseline: 1339 passed, 6 browser tests skipped; 454 backend files matched the original checkout. Main base is `ab75c5221983d0741035bb2468b454b59b52263e`.
WSL runner instructions and exact log paths are supplied in each task dispatch, not committed as product scripts.
