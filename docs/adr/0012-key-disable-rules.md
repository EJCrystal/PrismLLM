# 0012 密钥禁用规则与自动恢复

## 背景

V30 的自动禁用只有两个标量门槛：连续 401/403 次数，以及窗口内失败次数。两者最终都落到同一个 `channel_keys.enabled = false`，没有到期时间，也无法区分人工停用和系统停用。实际运行暴露两类问题：

- 上游用 403 返回「地区不支持」、用 401 返回「模型未开通」时，会和「凭据失效」一起计数，停掉一把仍然可用的密钥。
- 短期 401（例如 token 轮换窗口）会被永久停用，只能人工启用；而人工停用的密钥和自动停用的密钥共用 `enabled`，一旦引入自动恢复就无法区分。

对照 sub2api、axonhub、CLIProxyAPI：三者都用「状态码 + 错误文本/错误码」判定，都把临时禁用和永久禁用分开，临时禁用一定带到期时间。

## 决策

把「连续/窗口两个全局阈值」替换为**有序规则列表**，每条规则自带匹配条件、计数方式、动作和恢复策略。命中第一条即停止，规则之间不叠加。

```
KeyDisableRule {
  id, name
  statusCodes: int[]              // 空 = 不按状态码过滤
  statusCodeAtLeast: int?         // 例如 500，与 statusCodes 取并集
  bodyPatterns: string[]          // RE2 正则，大小写不敏感；空 = 不按文本过滤
  threshold, windowMinutes
  streak: bool                    // true = 连续计数（成功清零）；false = 窗口计数（成功不清零）
  action: TEMPORARY | PERMANENT
  disableMinutes: int?            // TEMPORARY 必填
  includeTransportFailures: bool  // 连接失败/超时是否计入
}
```

恢复方式由动作推导，不单独配置：`TEMPORARY` 到期即视为可路由，首个成功请求完成恢复并清空计数；`PERMANENT` 不会自动恢复，只能由管理员人工启用，凭据可保留或替换。

密钥状态从 `enabled` 扩展为四态：`ACTIVE`、`MANUAL_DISABLED`、`AUTO_TEMPORARY`、`AUTO_PERMANENT`。规则只产生后两种；人工停用永不被自动恢复任务改写。

- **临时禁用**写 `auto_disable_until`。路由判断以「到期即视作可用」为准，不依赖后台任务；到期后用 `probe_until` 原子抢占单探测租约，并发请求不会同时打上游。探测成功恢复 `ACTIVE` 并清空计数；失败按命中规则重新计时或转永久停用。后台清理任务只收尾「已到期且没有有效探测租约」的临时停用。
- **永久禁用**不设到期时间，只能由管理员人工启用，沿用现有 `stateVersion` 并发保护；无需强制更换凭据，便于在余额充值、账号解封或短暂故障恢复后复用原密钥。启用会清空失败计数与密钥级冷却，若问题仍在则请求会再次命中规则。
- 规则变更使旧计数失效：计数行记录当时的有效策略指纹，规则内容或作用域一变就不再复用（见下文「渠道级覆盖」）。
- **中性信号不打断连续计数**：只有成功清零连续计数；未命中任何规则的上游响应（400/404/422）既不计数也不清零。V30 把「其他上游响应」当作连续鉴权失败的中断，但请求参数错误并不能证明凭据可用，反而让调用方可以用无效请求掩盖一把坏钥匙。

默认规则按顺序匹配：401/402/403 且命中 `INSUFFICIENT_BALANCE` → 1 次永久禁用；401/403 且命中 `invalid_api_key`、`authentication_error`、`invalid_grant` 等稳定特征 → 连续 2 次永久禁用；401/403 账号停用/其他结算类 → 1 次永久禁用；其余 401/403 → 5 分钟内 3 次临时 30 分钟；429/5xx/传输层 → 5 分钟内 8 次临时 10 分钟；400/404/422 不参与。余额规则优先于凭据规则，避免混合错误体被后者提前匹配。

## 渠道级覆盖（V57）

全局规则只适合所有上游共有的失败特征。实际上游的错误体差异很大：「活动已关闭或 Key 失效」（`DEEPSEEK_ACTIVITY_DISABLED`）只由 Grox 返回，放进全局列表会命中其他渠道的同名文本。因此 V57 增加渠道级覆盖，管理台三选一：

| 形态 | 存储 | 实际执行的规则 |
| --- | --- | --- |
| 继承全局 | `key_disable_policy = null` | 全局规则 |
| 自定义 | 非空且 `enabled = true` | 渠道规则在前，未命中时回落全局规则 |
| 关闭 | 非空且 `enabled = false` | 不执行任何规则（渠道规则和全局规则都不生效） |

- **合并方式是「渠道优先 + 全局兜底」，不是整表替换。** 管理台保存的渠道策略只包含管理员为该渠道新增的规则，其余规则随全局设置更新自动生效。合并后的列表仍按顺序「首条命中即止」：渠道规则命中后只按它计数，即使尚未达到阈值也不会再落到全局规则，否则一次失败会被两套规则各记一次。全局自动禁用已关闭时不再兜底它的规则，否则「关掉全局」对配置了渠道规则的渠道仍然生效，与直觉相反。
- **同 id 时渠道版本接管**：渠道规则与全局规则同 id 不会报错，而是让全局那条不再参与兜底，等价于「在渠道里收紧某条全局规则」。计数桶本来就按密钥和规则 id 隔离，不存在两套规则共用计数的问题；V57 搬迁到 Grox 的整份规则副本也因此照常可编辑。代价是渠道里一条更宽的规则可能遮蔽全局更精确的规则，管理台对此有显式提示。
- **关闭形态不需要规则**：`enabled = false` 时允许空规则列表，渠道只表达「本渠道不执行自动禁用」。`recordOutcome` 在策略关闭时直接清空该密钥的健康计数并返回。
- `channels.key_disable_policy_revision` 每次保存覆盖策略自增；全局设置沿用 `key_auto_disable_settings.revision`。计数归属由**有效策略指纹**判定，存在 `channel_key_health.policy_revision`：指纹是 `作用域 + 合并后策略 JSON` 的 SHA-256 截断（63 位非负）。只比渠道版本号会漏掉「全局规则改了、渠道有效策略跟着变」的情况，用旧规则累计的失败会误触发新规则。
- 渠道策略的保存、清除与审计在同一事务提交；`PUT /admin/api/channels/{id}/key-disable-policy` 传 `{"override": null}` 恢复继承，传 `{"override":{"enabled":false,"rules":[]}}` 关闭。响应返回 `mode`、`override`、`globalPolicy` 与合并后的 `effective`。
- 管理台在渠道操作菜单提供「密钥自动禁用（继承全局 / 已自定义，未命中回落全局 / 已关闭）」入口。切到自定义时优先沿用已保存的渠道规则，没有则新建一条草稿规则——不拿全局规则当初始值，否则等于把全局规则整份复制成渠道版本，全局后续更新不再自动生效。
- V57 迁移把 `activity_disabled` 从全局规则移到 Grox 的渠道覆盖，并把阈值改为 1：这是上游终态错误，等不到 5 分钟窗口内的第二次。

## 边界

- 只匹配上游错误体，请求体截断后参与匹配，不写日志、不写审计、不返回调用方。
- 正则保存时编译校验，单条模式长度上限 200 字符；运行时不静默忽略非法规则。
- 不做任意 AND/OR 组合、不做每规则独立窗口外的策略编排、不做 `permanent_disable_delete`。
- 自动禁用仍与候选冷却独立：冷却负责「这个候选现在不好」，自动禁用负责「这把钥匙坏了」。

## 后果

- V32：`channel_keys.state` 枚举替换原 `enabled` 布尔列，新增 `auto_disable_until bigint`；旧数据按 `enabled = true → ACTIVE`、`enabled = false and auto_disabled_at is not null → AUTO_PERMANENT`、其余 `MANUAL_DISABLED` 回填。
- V35：`channel_keys.probe_until bigint` 保存探测租约；后台任务按 `KEY_AUTO_RECOVERY_INTERVAL_MS` 周期收尾已到期且无租约的临时停用。
- V33：`key_auto_disable_settings.rules text` 保存规则列表；`channel_key_health.rule_counters text` 保存按规则分桶的失败时间戳。两者为空时分别回退到内置默认规则和空计数。
- V57：`channels.key_disable_policy` / `key_disable_policy_revision` 保存渠道覆盖策略；`channel_key_health.policy_scope` 标记计数归属，`channel_key_health.policy_revision` 复用为有效策略指纹，升级前的计数默认为 `global`。
- 规则引擎在 `resilience/KeyDisableRules.kt`，请求路径通过 `KeyAttemptSignal` 把状态码、截断后的上游错误体和传输层失败传入 `KeyAutoDisableRepository.recordOutcome()`；停用与审计在同一事务提交。
- `KeyAutoDisablePolicy` 仅保留为旧管理 API 的兼容适配器；新规则通过 `KeyDisablePolicy` 直接保存。
- 客户端取消、网关本地限流、请求体错误继续不计数；未命中任何规则的上游响应也不计数，且不打断连续计数。

可视化方案与规则试跑见 `docs/design/key-auto-disable-rules.html`，V57 的迁移与部署核对项见 `docs/migrations/V57-channel-key-disable-policy.md`。
