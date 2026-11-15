# 调用方密钥黑名单 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 三维访问范围支持与白名单互斥的黑名单，所有数据面入口保持一致边界。

**Architecture:** nullable allowed/denied 集合表达三模式，独立 denied 表与模式标记原子存储。现有路由快照与 Vue 表单分别扩展匹配谓词和三模式显示。

**Tech Stack:** Kotlin、Ktor、Exposed、PostgreSQL/Flyway、Vue 3、TypeScript。

**Spec:** `docs/superpowers/specs/2026-11-15-client-key-access-denylist-design.md`

## Global Constraints

- 模型 ID 精确匹配且区分大小写，无通配符；Key 身份为 `(channelId, keyId)`。
- 静态 Key 黑名单保留 OAuth；渠道黑名单排除该渠道全部凭据。
- 空白名单拒绝全部，空黑名单不额外限制；既有 404/403/503 分类保持。
- 省略/null 更新保留，完整对象原子替换，轮换/启停/删除不改变模式。
- 不修改 V68，不新增依赖；新增 Kotlin 代码具备 KDoc。

## Review Focus

- 黑名单全部排除支持模型的凭据时应 403；没有配置凭据且未排除任何凭据时仍为 503（Task 2）。
- 空黑名单的存储模式保留，但实际授权与旧不限制一致（Task 1、2、3）。
- 切换白/黑名单后旧关联不能残留，DB 同维度 CHECK 禁止两标记同时为真（Task 1）。
- 静态 Key 与同 ID 的 OAuth 不混淆；跨维度排除及恢复时间不能泄漏范围外凭据（Task 2）。
- 隐藏草稿、删除对象、空黑名单不误报“无授权目标”（Task 3）。

---

### Task 1: 管理 API 与持久化

**Files:** quota/domain/ClientKeyAccessPolicy.kt、quota/persistence/ClientKeyAccessTables.kt、ClientKeyTables.kt、ClientKeyRepository.kt；api/ClientKeyAccessDtos.kt、ClientKeyAccessPolicies.kt；V69__client_key_access_denylist.sql；ClientKeyAccessPolicyIntegrationTest.kt、RequestLogAuditIntegrationTest.kt。

**Interfaces:** 新增 `deniedChannelIds: Set<String>?`、`deniedChannelKeys: Set<ClientKeyAllowedChannelKey>?`、`deniedModelIds: Set<String>?`；DTO 保持格式，新增 mode `denylist`。

- [x] 先新增真实管理 API 黑名单往返、空名单保留、模式切换和生命周期测试。
- [x] 执行 `ClientKeyAccessPolicyIntegrationTest`，确认未知 denylist 导致行为断言失败。
- [x] 扩展领域、V69、三表、批量快照读写、DTO 与校验；同维度互斥。
- [x] 执行受影响持久化/API/迁移回归，确认全部通过并提交。

### Task 2: 路由授权

**Files:** routing/domain/candidate/RoutingAccessScope.kt、routing/readmodel/UpstreamModelCatalog.kt、api/ClientKeyRoutingAccess.kt；RoutingAccessScopeTest.kt、ClientKeyRoutingAccessIntegrationTest.kt。

**Interfaces:** 路由快照新增同名三个 denied 集合（Key 为 RoutingAllowedChannelKey）；现有 allowsModel/allowsChannel/allowsCredential 保持签名。

- [x] 新增管理策略已保存但数据面尚未过滤的真实请求测试，确认越权成功导致断言失败。
- [x] 扩展统一谓词、快照转换和目录授权诊断；黑名单不得影响 OAuth 身份。
- [x] 验证四协议拒绝、授权请求、列表、重试/兜底、无凭据、空名单、恢复提示回归。
- [x] 回归通过后提交。

### Task 3: 管理台与交付

**Files:** web/types/api.ts、utils/clientKeyAccess.ts、components/ClientKeyAccessFields.vue；三个访问范围前端测试；README.md、CONTEXT.md、docs/routing-and-resilience.md。

**Interfaces:** `ClientKeyAccessMode` 增加 `denylist`，payload 仍为三个 `{mode, ids/keys}`。

- [x] 新增真实 helper/DOM 的黑名单保存、摘要、切换、目录失败和空名单测试，确认行为 RED。
- [x] 扩展三模式选择、多选、说明、摘要和失效提示；更新使用文档。
- [x] 前端全量、typecheck/build 与后端全量通过；核对测试镜像源码资源一致。
- [x] 独立整分支审阅；必要修复按 RED→GREEN 验证；记录证据并提交。
