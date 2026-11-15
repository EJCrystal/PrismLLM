# 调用方密钥黑名单交付验证

## 行为

渠道、静态渠道 Key、对外模型 ID 分别支持 `unrestricted`、`allowlist`、`denylist`。同维度互斥、跨维度取交集；空白名单拒绝全部，空黑名单不额外限制但保留模式。静态 Key 黑名单保留 OAuth，渠道黑名单排除该渠道全部凭据。模型 ID 精确匹配且区分大小写。

V69 在 V68 之后添加模式标记、关联表及互斥 CHECK，使用原仓储事务和批量一致快照。更新省略/null、启停、轮换行为保持。模型黑名单 ID 在映射删除后保留，完整编辑可以保留已有 ID；新增 ID 必须存在。重建同 ID 模型仍拒绝。

路由、列表、搜索内部回合、协议转换、重试、冷却兜底和恢复时间共用授权范围。全部支持凭据被排除时返回 403；原本没有支持凭据时仍返回 503；未配置模型仍返回 404。

## 验证证据

最终集成基线：`83f4d5d`，仅新增 VisionAssessor 相关修复；已无冲突合入功能分支。原工作区没有切换或修改。

| 检查 | 结果 | 本地证据 |
| --- | --- | --- |
| 管理 API 行为 RED | 13 项，3 个预期断言失败，0 errors | `backend-20261115-111028.log` |
| 管理/API/迁移受影响 GREEN | 37/37 | `backend-20261115-112819.log` |
| 路由行为 RED | 21 项，5 个预期断言失败，0 errors | `backend-20261115-113321.log` |
| 独立审阅修复 RED | 39 项，2 个预期断言失败，0 errors | `backend-20261115-121231.log` |
| 独立审阅修复 UI RED | 失效模型保留说明断言失败 | `final-ui-red.log` |
| 前端完整测试 | 594/594，0 失败/跳过 | `final-frontend-full.log` |
| 前端类型检查与构建 | 成功；保留已有 chunk 提示 | `final-typecheck.log`、`final-build.log` |
| 修复后后端完整 clean test | 成功，0 failures/errors，6 浏览器条件跳过 | `backend-20261115-122145.log` |
| 最新主干集成后 clean package | 1432 总计 / 1426 通过 / 6 跳过 / 0 failures/errors，150 suites；打包成功 | `backend-20261115-123055.log` |
| 源码资源一致性及胖 JAR V69 | 480 文件 SHA256 一致，胖 JAR 包含 V69；前端 dist 单独部署 | `final-backend-summary.json` |

完整本地证据位于 `.superpowers/sdd/2026-11-15-client-key-access-denylist/`。后端使用 WSL 原生镜像、真实 Docker/PostgreSQL，前端使用真实 DOM；没有声称执行新的 Chrome 视觉检查。管理和路由最初 GREEN 的一次搜索夹具计数隔离失败已修复，最终完整回归覆盖其两模式逐回合行为。

## 独立审阅与修复

[整分支审阅](2026-11-15-client-key-access-denylist-review.md) 对 `d8bd552..2c83a05` 给出 Critical 0 / Important 2 / Minor 0。主代理接受两项 Important，在唯一修复波次中先复现，再修复并执行完整测试，没有再派复审。

1. 删除最后映射后，完整编辑原模型黑名单曾返回 400。现在仅允许保留当前已有排除 ID，未知新增及大小写不同的 ID 仍拒绝；真实 API 覆盖名称/额度及其他维度修改、创建校验和同 ID 重建后 403。真实 DOM 增加准确的保留说明。
2. 支持凭据全部列黑时，另一条空凭据映射曾把 403 覆盖为 503。现在以授权模型/渠道交集中的全部支持凭据判断；跨协议确认稳定错误码、零上游调用及调用方租约释放。无凭据、无关 Key 黑名单及空黑名单原有 503 回归保持。

## 实施裁定及成本

1. 主代理连续实施、一次独立终审：减少逐任务重复阅读；成本是没有逐任务独立审阅。
2. Git transport 失败时由 GitHub API 导入主干签名 commit：先计算 SHA1 精确匹配再写入对象；成本是 fallback 复杂。后续 transport 已恢复，使用正常 fetch/push。
3. 复用相同 Git tree 的已验证基线：已核对 squash tree 一致；成本是 tree 不同会误用证据，本轮核对避免此风险。
4. Windows 原生账本替代 Bash helper：WSL Git 不能解析 Windows worktree gitdir；成本是人工记账，分提交和日志补充可追踪性。
5. 后端等待期间处理独立前端：DTO 已由批准设计固定；成本是任务记账交错，使用独立提交保留来源。
6. 支持凭据按允许模型/渠道交集诊断：范围外凭据不参与授权候选的可用性；成本是调用方若期待全局排除对象影响 403，则诊断语义不同。
7. 保留既有编译、Micrometer、shade、HMR、chunk 告警及白名单删除模型后的编辑限制：不扩大黑名单需求；成本是已知告警和原限制仍存在。六个 BrowserScreenshotRendererTest 因环境没有 Chrome/Chromium 跳过，其视觉行为未验证。
8. 保留本计划本地证据并归档审阅/验证摘要：便于 PR 复核，不重复尝试曾被拒绝的清理；成本是本地 scratch 占用保留。

延期 Minor：无。未对独立审阅的 Declined to judge 项作额外通过声明，裁定 7 明确保留其实际限制。

## 提交流程

遵循用户授权：创建 PR，检查当前 PR 的 CI、AI Review Gate 与待处理 review；仅在通过且无待解决意见时 squash merge，不使用管理员门禁绕过。远程结果以最终会话交付和 PR 页面为准。
