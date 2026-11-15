# 整分支独立代码审阅

审阅分支：`feat/client-key-access-denylist`。BASE：`d8bd552346204be4d0372c629aac296d99d92290`；HEAD：`2c83a0563170bf8250cd9f4e1433edfbbf3d36c1`。

依据：已批准设计、实施计划及其中五项 Review Focus；遵循 `requesting-code-review/code-reviewer.md`。已分管理持久化、路由接点、前端与测试三轮阅读全部 25 个文件的差异，并核对相邻 `AdminRoutes`、`ProxyRoutes`、`ExposedModels`、`ChannelRecord`、OAuth 目录及 V68。审阅没有修改源码、index、HEAD 或分支，没有派子代理，也没有重跑测试。仅新增本报告。`git diff --check BASE..HEAD` 无输出。

### Strengths

- 三种模式通过 nullable allowed/denied 集合保存，规范化拒绝同维度冲突，DTO 按非 null 标记还原模式；空黑名单不会在读写时丢失。新增领域/路由类型、转换函数和持久化辅助函数均有 KDoc。
- V69 保持 V68 不变，默认 denied 标记为 false；三个 CHECK 防止白/黑名单同时启用。渠道/静态 Key 关联级联删除，模型 ID 不跟随映射删除。仓储在同一事务中替换六类关联，三个读入口继续以 REPEATABLE READ 批量读取模式及关联。
- 路由谓词按 `(channelId, keyId)` 匹配静态身份，OAuth 不匹配静态黑名单，但受渠道黑名单约束。请求、两个列表、重试、冷却兜底和恢复时间都使用授权过滤后的候选；新增测试覆盖协议转换、搜索回合、探测租约和越权恢复时间。
- 前端三维模式切换复用同一份多选草稿，保存时仅清空 unrestricted 的数组；失效目标保留 ID，隐藏草稿及黑名单不触发白名单“无授权目标”提示。真实 DOM 和 helper RED 能证明功能增加确实改变了行为。

### Issues

#### Critical (Must Fix)

None。

#### Important (Should Fix)

1. **删除模型最后映射后，保留的模型黑名单阻止正常编辑调用方密钥。**
   - 文件：`prism-app/src/main/kotlin/dev/soven/prismllm/api/ClientKeyAccessPolicies.kt:49`。必要接点：`prism-app/src/main/kotlin/dev/soven/prismllm/api/AdminRoutes.kt:1990`、`web/src/components/ClientKeysView.vue:135`、`web/src/components/ClientKeyAccessFields.vue:187`。
   - 触发：先将 `m1` 加入模型黑名单，再删除它的最后一条模型映射。仓储按设计保留 `deniedModelIds = {m1}`；管理员打开调用方密钥，仅修改名称、额度、过期时间，或修改另一个访问维度并保留原模型黑名单，然后保存。
   - 实际行为：管理台始终完整提交 `accessPolicy`，第 49 行把已有 `deniedModelIds` 与白名单一起要求仍存在于 `configuredModels`，因此 PUT 返回 `400 invalid_client_key_access_policy`，所有编辑都被拒绝。当前界面的“请明确取消或重新选择”引导管理员删除应当保留的排除 ID；这样保存后，同 ID 模型重新配置时会失去原黑名单保护。省略/null 策略的 API 更新可以绕过该限制，但管理台正常编辑流程无法使用这个路径。
   - 影响：新的模型黑名单生命周期与正常管理操作不兼容，管理员被迫在保存其他修改和保留未来同 ID 禁止规则之间选择。设计明确要求模型黑名单 ID 在映射删除后保留，以防重新配置绕过；正常编辑应当能够保留这条规则。
   - 建议：更新校验接受当前策略，允许保留当前已有的模型黑名单 ID；创建及本次新加入的 ID 仍按现有配置和长度规则校验。PUT 已读取 `existing`，可用其 `accessPolicy.deniedModelIds` 判断哪些是合法保留项。同时把这类模型提示改为可保留的排除规则。补真实管理 API/DOM 回归：删除最后映射后保存名称及其他维度成功、黑名单不变、重建同 ID 后仍返回 403；未知新增 ID 继续返回 400。
   - 现有测试边界：`deleted denylist targets retain mode and model identity` 只读取后删除调用方密钥；前端失效模型测试只验证回显与手动取消，没有覆盖完整更新。

2. **空凭据映射会把“全部支持凭据已被黑名单排除”的 403 误分类为 503。**
   - 文件：`prism-routing/src/main/kotlin/dev/soven/prismllm/routing/readmodel/UpstreamModelCatalog.kt:101`。必要接点：同文件第 105 行、`prism-app/src/main/kotlin/dev/soven/prismllm/api/ProxyRoutes.kt:298`。
   - 触发：启用渠道 A 映射 `m1` 且有唯一支持它的静态 Key `keyA`；启用渠道 B 同样映射 `m1`，但没有任何支持凭据；两渠道支持相同请求协议。调用方静态 Key 黑名单为 `{(A, keyA)}`，其余维度 unrestricted。
   - 实际行为：A 被过滤掉，B 因 `supportingCredentials.isEmpty()` 被留在 `configured`。因此 `configured.isEmpty()` 为 false，`accessDenied` 为 false；候选为空但协议可达，最终返回 `503 model_credentials_unavailable`。实际上本模型全部现有支持凭据都已被黑名单排除。仅增加一个没有凭据的映射，就会使相同排除规则从 403 改成 503。
   - 影响：违反 Task 2 的 Review Focus：“黑名单全部排除支持模型的凭据时应 403；没有配置凭据且未排除任何凭据时仍为 503”。调用方把必须修改权限的拒绝当作上游暂时不可用处理，无法取得稳定的 `route_access_denied` 错误码。没有发现该分支会调用越权上游，问题是错误分类。
   - 建议：在模型级授权诊断中分别记录支持凭据是否存在、是否实际被名单排除、是否还有获准支持凭据，避免单条空凭据映射覆盖全排除结果。保留“原本没有配置支持凭据且未排除任何凭据”的 503 分支，以及既有渠道白名单授权空凭据渠道的行为。补真实请求回归，覆盖以上双渠道组合，预期 403、稳定错误码且零上游调用；同时保留现有 noKeys + 无关黑名单及空黑名单的 503 回归。
   - 现有测试边界：单渠道全部排除与 noKeys + 无关 Key 黑名单分别有测试，但没有组合“一个被全部排除的支持渠道 + 一个空凭据映射”。

#### Minor (Nice to Have)

None。

### Recommendations

先针对上述两个具体触发场景补行为 RED，再做局部修复与受影响回归。无需扩展成权限模型重构，也无需处理已有无关告警。

### Validation evidence

本次审阅读取已有证据，没有重新执行测试：

- 管理 RED `backend-20261115-111028.log`：13 测试、3 个断言失败、0 error；路由 RED `backend-20261115-113321.log`：21 测试、5 个断言失败、0 error。前端 `frontend-red.log` 与 `frontend-view-red.log` 合计 4 个真实行为断言失败。
- 受影响管理/持久化回归 37 通过；最终后端 `backend-20261115-114856.log` 显示 Reactor 全部 SUCCESS，`backend-summary.json` 汇总 150 suites、1406 总计、1400 通过、0 failure/error、6 条 `BrowserScreenshotRendererTest` 条件跳过。6 条跳过未计为通过。
- `frontend-full.log`：594/594、0 失败、0 跳过；`typecheck.log` 与 `build.log` 完成。新增真实前端测试读到的是保存 payload、模式切换、目录/失效提示行为。
- 中间 web-search fixture 首请求计数失败已按每种权限清空记录修复；最终全量覆盖该测试并通过。本报告没有将这次已修复的 fixture 隔离问题视作生产缺陷。
- 已有绿色证据未覆盖上述两个组合边界；两个发现来自所列生产分支与相邻调用点的确定性控制流分析。

### Declined to judge

- 已知 Kotlin/Java 编译告警：本分支未引入对应行为变化，按既有噪音保留，不扩大成本要求清理。
- 已知 Micrometer 告警：不属于本次三维访问名单行为，未要求修复原有指标问题。
- 已知 shade 重复资源告警：没有证据表明影响此次授权实现，不要求打包重构。
- 已知 Vue 测试 HMR 噪音：现有真实 DOM 测试完成，不作为此次功能缺陷。
- Vite 超过 500 kB 的 chunk 提示：现有构建完成，代码拆包不属于本计划。
- 浏览器截图渲染的六个条件跳过用例：本轮没有执行其视觉行为，未对该已有功能作通过结论；它们不覆盖访问黑名单。
- 白名单已有的删除模型后完整编辑限制：发现 1 仅要求新模型黑名单能保留已有禁止规则，没有把旧白名单行为扩展成本分支的重构任务。

### Assessment

**发现总数：Critical 0 / Important 2 / Minor 0。**

**Ready to merge? With fixes。**

核心持久化、快照和候选授权边界实现完整，已有全量证据也可靠；但删除后模型黑名单的正常编辑，以及多渠道全排除诊断，仍需要两个范围明确的修复及回归后再合并。
