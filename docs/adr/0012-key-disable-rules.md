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
- 规则变更通过 `KeyAutoDisableSettings.revision` 使旧计数失效，沿用现有实现。
- **中性信号不打断连续计数**：只有成功清零连续计数；未命中任何规则的上游响应（400/404/422）既不计数也不清零。V30 把「其他上游响应」当作连续鉴权失败的中断，但请求参数错误并不能证明凭据可用，反而让调用方可以用无效请求掩盖一把坏钥匙。

默认规则按顺序匹配：401/402/403 且命中 `INSUFFICIENT_BALANCE` → 1 次永久禁用；401/403 且命中 `invalid_api_key`、`authentication_error`、`invalid_grant` 等稳定特征 → 连续 2 次永久禁用；401/403 账号停用/其他结算类 → 1 次永久禁用；其余 401/403 → 5 分钟内 3 次临时 30 分钟；429/5xx/传输层 → 5 分钟内 8 次临时 10 分钟；400/404/422 不参与。余额规则优先于凭据规则，避免混合错误体被后者提前匹配。

## 边界

- 只匹配上游错误体，请求体截断后参与匹配，不写日志、不写审计、不返回调用方。
- 正则保存时编译校验，单条模式长度上限 200 字符；运行时不静默忽略非法规则。
- 不做任意 AND/OR 组合、不做每规则独立窗口外的策略编排、不做 `permanent_disable_delete`。
- 自动禁用仍与候选冷却独立：冷却负责「这个候选现在不好」，自动禁用负责「这把钥匙坏了」。

## 后果

- V32：`channel_keys.state` 枚举替换原 `enabled` 布尔列，新增 `auto_disable_until bigint`；旧数据按 `enabled = true → ACTIVE`、`enabled = false and auto_disabled_at is not null → AUTO_PERMANENT`、其余 `MANUAL_DISABLED` 回填。
- V35：`channel_keys.probe_until bigint` 保存探测租约；后台任务按 `KEY_AUTO_RECOVERY_INTERVAL_MS` 周期收尾已到期且无租约的临时停用。
- V33：`key_auto_disable_settings.rules text` 保存规则列表；`channel_key_health.rule_counters text` 保存按规则分桶的失败时间戳。两者为空时分别回退到内置默认规则和空计数。
- 规则引擎在 `resilience/KeyDisableRules.kt`，请求路径通过 `KeyAttemptSignal` 把状态码、截断后的上游错误体和传输层失败传入 `KeyAutoDisableRepository.recordOutcome()`；停用与审计在同一事务提交。
- `KeyAutoDisablePolicy` 仅保留为旧管理 API 的兼容适配器；新规则通过 `KeyDisablePolicy` 直接保存。
- 客户端取消、网关本地限流、请求体错误继续不计数；未命中任何规则的上游响应也不计数，且不打断连续计数。

可视化方案与规则试跑见 `docs/design/key-auto-disable-rules.html`。
