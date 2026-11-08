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
`x-goog-api-key`；请求还会带 `User-Agent` 与 `X-Goog-Api-Client`。

账号配置了 `proxy_url` 时，`loadCodeAssist` / `onboardUser` / `fetchAvailableModels` /
`retrieveUserQuotaSummary` 与对话请求一样经该代理发出，与 token 刷新共用同一个出口
（`CodeAssistClientPool` 按代理地址复用连接池，未配置代理时沿用应用共享客户端）。
project/tier 缓存也按出口校验：账号代理一变就重新协商，不等缓存过期。

## 请求信封

```json
{
  "model": "gemini-3.7-flash-high",
  "project": "cloud-project-id",
  "request": { "contents": [], "generationConfig": {}, "sessionId": "-123456789" },
  "userAgent": "antigravity",
  "requestType": "agent",
  "requestId": "agent-0123456789ab"
}
```

`request` 里是标准 Gemini 请求体，不带 `model`；`sessionId` 由首条用户文本的
SHA-256 派生，让同一段对话落在同一个会话上，取不到文本时用随机值。

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
Antigravity 把一个模型按思考等级拆成多个 id（`gemini-3.7-flash-low` /
`-medium` / `-high`），网关把它们收成一个"家族"模型对外暴露，请求时再按
`thinkingLevel`（或由 `thinkingBudget` 换算的等级）选最近的一档；
名称括号里的等级优先于 id 后缀。没有任何等级时优先用家族本身的 id，其次 `high`。

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

## 尚未实现

- Antigravity 的 Gemini schema 兼容改写（数组 items、空 enum、根 union、display items、绘图）。
  这部分要先和现有 `GeminiMapping` 逐条比对，不能直接照搬 magpie 的实现。
- 真账号端到端验证。
