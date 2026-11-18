# Google Code Assist（Antigravity）

Antigravity 的订阅凭据不能直接调用 Gemini API：官方客户端走的是 Google Code Assist，
路径、认证与响应都比 Gemini 多一层"信封"。本文件记录网关这一侧的实现约定，
规则来自 magpie 的 `internal/provider/google.go` 与 `internal/gateway/codeassist.go`。

## 端点与动作

所有动作都是 `POST {base}/v1internal:{action}`，`base` 默认
`https://daily-cloudcode-pa.googleapis.com`，`loadCodeAssist` 另有
`https://cloudcode-pa.googleapis.com`。

| action | 用途 |
| --- | --- |
| `loadCodeAssist` | 发现账号的 cloud project 与 tier |
| `onboardUser` | 首次开通，拿 project（长轮询，最多 5 次） |
| `fetchAvailableModels` | 模型目录，可按 tier 试错 |
| `streamGenerateContent` / `generateContent` | 对话 |
| `retrieveUserQuotaSummary` | 额度读数 |

认证统一是 `Authorization: Bearer <access_token>`，不是 Gemini 的
`x-goog-api-key`；请求还会带 `User-Agent`。Antigravity 的 CCPA 数据面请求**不带**
`X-Goog-Api-Client`（目录/刷新接口曾经带过，网关已一并移除）。网关仍会追加 W3C
`traceparent` 用于链路关联；这是网关自己的可观测性头，本次 Antigravity 客户端采样未见该头。
Code Assist 流式请求不显式设置 `Accept: text/event-stream`，但 Ktor 会自动补上
`Accept: */*`；这不等于传输时完全没有 `Accept`。

内置 UA 使用 `antigravity/hub/2.32.1 (aidev_client; os_type=linux; arch=amd64; cl=1021296516)`。
运行时优先使用数据库请求头目录和固定版本，代码默认值不会覆盖已有的同版本 profile。
因此升级源码后仍需核对实际生效的 UA，不能只看内置模板；官方版本清单也不会更新 `cl` 构建号。
V77 迁移只修正完全匹配旧默认值 `antigravity/hub/2.32.1 windows/amd64` 的那一行，
自定义 headers/name/source、pin、其他版本与目录状态都保持不变。

账号配置了 `proxy_url` 时，`loadCodeAssist` / `onboardUser` / `fetchAvailableModels` /
`retrieveUserQuotaSummary` 与对话请求一样经该代理发出，与 token 刷新共用同一个出口
（`CodeAssistClientPool` 按代理地址复用连接池，未配置代理时沿用应用共享客户端）。
数据面的对话请求由 `GatewayApplication.accountEgressClients` 按代理地址复用客户端，
网关关闭时一并关闭。账号代理配置无效时数据面直接返回 `400 invalid_oauth_proxy`，
不会静默回落直连。

Code Assist 的发现、目录与额度请求都有 30 秒上限（直连请求用带超时的派生客户端），
数据面的准备阶段（发现 + 目录合计）另有 `min(请求超时, 20s)` 的整体上限，
随后对话请求只使用这份预算剩下的时间。上游错误同样是
`{"error": ...}` 信封，网关先解包再交给 Gemini 错误识别。

会话 id 由客户端别名、渠道、调用方 API Key 与可选的会话标识（`x-session-id` /
`x-conversation-id` 等）派生。这些成分在多轮对话里都不变，所以同一会话的 id 稳定；
不使用每次请求都会变的 `x-request-id` / traceparent，也不用请求正文，因此不同客户端
即使首条用户文本相同也不会落到同一个上游会话。
project/tier 缓存也按出口校验：账号代理一变就重新协商，不等缓存过期。

## 请求信封

```json
{
  "project": "aicode-consumers",
  "requestId": "agent/<cascade-id>/<epoch-ms>/<trajectory-id>/<step>",
  "request": {
    "contents": [],
    "systemInstruction": { "role": "user", "parts": [] },
    "tools": [],
    "labels": {
      "cascade_id": "<cascade-id>",
      "root_cascade_id": "<cascade-id>",
      "trajectory_id": "<trajectory-id>",
      "request_id": "<trajectory-id>-<step-1>",
      "last_step_index": "<step-1>",
      "used_claude": "false",
      "used_claude_conservative": "false",
      "used_non_gemini_model": "false"
    },
    "generationConfig": {
      "maxOutputTokens": 262144,
      "thinkingConfig": { "includeThoughts": true, "thinkingLevel": "HIGH" }
    },
    "sessionId": "-123456789"
  },
  "model": "gemini-4-hydrogen-tiered-high",
  "userAgent": "antigravity",
  "requestType": "agent"
}
```

`userAgent` 是固定短字符串 `antigravity`，不是请求头里的完整 UA；`requestType`
对对话请求固定为 `agent`（标题生成器是 `checkpoint`，网关不模拟）。本次主 agent
请求采样未包含 `userPromptId`、`toolConfig`、`safetySettings` 或 `X-Goog-User-Project`。
网关默认不补这些字段，但会保留调用方原有的 `toolConfig` / `safetySettings`。

`generationConfig` 由网关按选中的变体补默认值：能从目录解析输出上限时补
`maxOutputTokens`，已知档位的 `thinkingConfig.thinkingLevel` 用 `LOW` / `MEDIUM` /
`HIGH` 字符串；`xhigh` 档 Antigravity 用数字 `100`。调用方显式设置的值优先。
存在 `thinkingBudget` 或 `thinkingLevel` 时不再额外注入档位；没有已知档位时也不强制
设为 `LOW`。缺失的 `includeThoughts` 默认补为 `true`。

`labels` 里的 `cascade_id` / `trajectory_id` 由 scope（别名、渠道、API Key ID、
调用方会话标识）稳定派生；`request_id` 为 `<trajectory-id>-<step-1>`。
`last_step_index` 当前用 `max(contents.size - 1, 0)` 近似，外层步号加一；
这不是 Antigravity 持久化的真实生成计数器。

`request` 里是标准 Gemini 请求体，不带 `model`；`sessionId` 只由调用方归属派生
（模型别名、渠道、API Key ID 与调用方声明的会话标识 `x-session-id` / `x-conversation-id` /
`conversation-id` / `session-id` 之一）。请求正文不参与派生，不同客户端的 scope 相互隔离。
数据面始终构造 scope：未声明会话标识时，同一别名、渠道和 API Key ID 仍会复用派生值，
无法区分该调用方的两段对话，调用方应提供会话头。仅直接调用信封工具且未提供 scope 时
才会生成随机值。

## 响应

- 单次：`{"response": {...普通 Gemini 响应...}}`
- 流式：每条 `data:` 都是 `{"response": {...}}`，也兼容裸 chunk；
  错误事件可能是 `{"error": {...}}`

网关在写回客户端之前统一解包，内部仍按普通 Gemini 处理，协议转换、用量统计与日志都不受影响。

## project 与 tier

1. `loadCodeAssist` 带 `{"metadata":{"ideType":"ANTIGRAVITY"}}`，读
   `cloudaicompanionProject`（字符串或 `{id}`）与 `currentTier` / `paidTier`。
2. 没有 project 时调 `onboardUser`：带 `tier_id` 与
   `{"ide_type":"ANTIGRAVITY","ide_version":...,"ide_name":"antigravity"}`，轮询 `done` 最多 5 次。
3. 结果写入 `oauth_accounts.upstream_project` / `upstream_tier` /
   `upstream_tier_checked_at`，一小时内复用；刷新失败沿用上一次的值。

## 模型目录与家族

`fetchAvailableModels` 的请求体是 `{"project": ...}`，可选
`{"entitlement":{"userTier": "<tier>"}}`。网关按 tier 从高到低尝试，某个 tier 被 400
拒绝后记 24 小时不再尝试，最后再用不带 entitlement 的请求兜底。

返回的 `models` 里会过滤掉 `disabled`、`chat_` / `tab_` 前缀与若干非对话模型。
目录按 cloud project 缓存（`CodeAssistModelCatalog.fetchFor` 返回与该 project 绑定的
`Catalog`），不同账号的目录互不串用；条目有 6 小时有效期，同 project 协商到的 tier
变化（套餐切换）也立即失效并要求重新拉取。无 entitlement 的兜底目录对任何套餐都成立，
不因 tier 变化失效。
Antigravity 把一个模型按思考等级拆成多个 id（`gemini-3.7-flash-low` /
`-medium` / `-high`），网关把它们收成一个"家族"模型对外暴露，请求时再按
字符串 `thinkingLevel` 选最近的一档；名称括号里的等级优先于 id 后缀。
当前会保留 `thinkingBudget` 和数值 `thinkingLevel`，但尚不从它们推导变体档位。
没有任何等级时优先用家族本身的 id，其次 `high`。

管理面刷新目录写渠道时，家族对外仍用家族 id，但 `upstream_model` 登记的是家族
默认变体，`canonical_model_id` 指向家族 id。因此数据面目录补拉失败或超时时，
请求发出的也是上游真实存在的模型，而不是可能不存在的家族 id。

## 提示词兼容

Antigravity 会对少数系统提示行返回 429 `Resource has been exhausted`，这与额度无关。
网关沿用 magpie 的做法，在命中行的第一个字符后插入零宽空格（U+200B）：

- `built on Anthropic's Claude Agent SDK`
- `You are Codex, an agent based on GPT-5`
- `You are Codex, a coding agent based on GPT-5`
- `Hermes Agent, an intelligent AI assistant created by Nous Research`

只改 `systemInstruction` 的文本，其他字段保持原样。

## 封禁账号

上游返回 403 `PERMISSION_DENIED`，正文包含 `This service has been disabled in this
account for violation of Terms of Service` 时，按**两小时账号级冷却**处理并继续换候选。
这类账号只能靠申诉恢复，不能靠等待重试。

## 额度

`retrieveUserQuotaSummary` 返回按组划分的桶，`window` 为 `5h` 与 `weekly`
的两类桶分别映射到额度快照的 primary 与 secondary：`usedPercent = (1 - remainingFraction) * 100`，
`resetTime`（RFC3339）换算成毫秒。`disabled` 的桶跳过。

## 验证记录（2026-11-17）

- `CodeAssistIntegrationTest` 新增两条原生 Gemini 数据面回归，使用实际的 Antigravity
  provider 渠道、隔离 PostgreSQL 与虚拟 OAuth 凭据。全部上游请求（含发现、目录、额度和
  models.dev 启动请求）都由 MockEngine 接管，不使用真实账号或访问真实 Google。
- 两条路由检查运行时目录 UA、`X-Goog-Api-Client` 缺席、有效的网关 `traceparent`、
  `Accept: */*`、信封/labels/sessionId、输出上限保留，以及 JSON/SSE 解包和结束帧之后的用量。
  定点回归通过；完整结果以全量测试与 CI 为准。
- 另以获准账号的有效缓存 token 执行当前信封与身份头代码的真实 Google smoke：
  使用新内置的完整 2.32.1 UA、`gemini-3.5-flash-lite`、`maxOutputTokens=64`，
  JSON 与 SSE 均返回 HTTP 200，并收到输出、`STOP` 和用量。smoke 本身只读访问生产数据库，
  没有主动刷新凭据、修改账号/渠道绑定或重启现有网关；旧默认目录行的单独修复见上文 V77。
- 这两层验证分别证明完整隔离路由的组装行为，以及当前封装代码被真实上游接受；
  不代表现有部署已升级，也不覆盖所有模型、OAuth 刷新和真实代理出口。

## 尚未实现

- Antigravity 的 Gemini schema 兼容改写（数组 items、空 enum、根 union、display items、绘图）。
  这部分要先和现有 `GeminiMapping` 逐条比对，不能直接照搬 magpie 的实现。
- 已部署网关连接真实 Google 的完整端到端验证；本次采用隔离路由回归与当前封装代码的真实上游 smoke。
