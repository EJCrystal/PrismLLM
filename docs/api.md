# PrismLLM API 手册

PrismLLM 分为面向客户端调用的**数据面接口**和由管理员专用的**管理面 API**。

---

## 一、数据面接口（Data Plane）

所有数据面调用需通过调用方密钥（Client Key，前缀 `sk-gw-...`）鉴权。

### 1. OpenAI Chat Completions

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{
    "model": "fast-chat",
    "stream": true,
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### 2. OpenAI Responses

```bash
curl http://localhost:8080/v1/responses \
  -H "Authorization: Bearer sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{
    "model": "fast-chat",
    "stream": true,
    "input": "你好"
  }'
```

### 3. Anthropic Messages

支持 Bearer Token 或 `x-api-key` 鉴权头：

```bash
curl http://localhost:8080/v1/messages \
  -H "x-api-key: sk-gw-..." \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-fast",
    "max_tokens": 128,
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### 4. Google Gemini 格式调用

Gemini 原生路由端点支持 Bearer 鉴权、`x-goog-api-key` 或 `?key=`：

```bash
curl "http://localhost:8080/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse" \
  -H "x-goog-api-key: sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{
    "contents": [{"role": "user", "parts": [{"text": "你好"}]}]
  }'
```

### 5. 可用模型发现

- `GET /v1/models`：以 OpenAI 标准格式返回当前已启用渠道所暴露的对外模型 ID，附带 `owned_by: "PrismLLM"`。
- `GET /v1beta/models`：以 Gemini 格式返回模型列表。

---

## 二、管理面接口（Admin API）

管理面接口均挂载在 `/admin/api/*`，由会话 Cookie 与 CSRF 令牌保护。

### 1. 登录与 CSRF 机制

```bash
curl -c cookies.txt -X POST http://localhost:8080/admin/api/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "your-password"}'
```

- 登录成功后返回 `HttpOnly` 的会话 Cookie 以及一个 `gw_csrf` Cookie。
- **所有后续写操作（POST / PUT / DELETE）** 必须在请求头中附带：
  `X-CSRF-Token: <gw_csrf 的值>`。

### 2. 渠道上游与密钥池配置

渠道提交采用多密钥池（Key Pool）模型：

```json
{
  "name": "openai-primary",
  "baseUrl": "https://api.openai.com/v1",
  "authType": "bearer",
  "protocolAuthTypes": { "responses": "anthropic" },
  "protocols": ["chat_completions", "responses"],
  "enabled": true,
  "modelMappings": [
    { "upstreamModel": "gpt-4o-mini", "canonicalModelId": "fast-chat" }
  ],
  "keys": [
    {
      "name": "primary-key",
      "credential": "sk-upstream-1",
      "state": "ACTIVE",
      "priority": 1,
      "models": ["gpt-4o-mini"],
      "rateLimit": {
        "enabled": true,
        "requestsPerMinute": 60,
        "maxConcurrentRequests": 3
      }
    },
    {
      "name": "backup-key",
      "credential": "sk-upstream-2",
      "state": "ACTIVE",
      "priority": 2,
      "models": ["gpt-4o-mini"]
    }
  ]
}
```

- `state`：密钥状态。
  - **客户端可提交状态**：`ACTIVE`（正常启用）、`MANUAL_DISABLED`（人工停用）。
  - **接口可返回的状态**：包含上述人工状态，以及由网关容灾规则自动触发的 `AUTO_TEMPORARY`（自动临时停用，到期后通过单探测抢占租约自愈）与 `AUTO_PERMANENT`（自动永久停用，需管理员人工重新启用）。客户端提交 `AUTO_*` 自动状态会被拒绝。
- 凭据加密：网关入库前使用 `GATEWAY_MASTER_KEY` 进行 AES-256-GCM 加密，前端界面和读取接口永不回显明文。

### 3. 模型发现与协议探测

- **保存前发现上游模型**：
  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/model-discovery \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"baseUrl": "https://api.openai.com/v1", "credential": "sk-..."}'
  ```
- **探测渠道协议支持度**：
  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/probe-protocols \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"model": "gpt-4o-mini"}'
  ```
- **单模型快速连通性测试**：
  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/model-test \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"model": "gpt-4o-mini", "protocol": "chat_completions", "prompt": "请只回复 OK"}'
  ```

### 4. 冷却可见性与手动解除

- `GET /admin/api/channel-health?window=24h` 的每个渠道摘要包含 `cooldownUntil`、`cooldownReason`、`coolingCandidates` 和 `totalCandidates`，用于渠道列表展示冷却剩余时间与主因。
- `GET /admin/api/channels/<id>/health` 的 `availability` 列表给出每个密钥/模型组合的状态、冷却原因和截止时间。
- 手动解除冷却：

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/cooldown/clear \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"scope": "channel"}'
  ```

  - `scope=channel`：清除该渠道下渠道键、密钥键和候选键的全部冷却。
  - `scope=key`：需同时提供 `keyId`，清除该密钥及其候选冷却。
  - `scope=candidate`：需同时提供 `keyId` 与 `upstreamModel`，只清除该候选。
  - 操作写入管理操作审计，动作为 `channel.cooldown.clear`。

### 5. 订阅账号（OAuth）

订阅账号与渠道密钥一对一绑定，refresh token 加密保存在密钥凭据列，access token 由网关自动刷新。详细语义见 ADR 0016。

- 查看可用的 OAuth 提供方：`GET /admin/api/oauth-providers`，返回 `id`、`label`、`configured` 与 `loginSupported`；未配置客户端参数的提供方不能导入，`loginSupported=false` 的提供方只能手动导入 refresh token。
- 账号看板：`GET /admin/api/oauth-accounts`，返回账号、渠道/密钥名称、授权状态、token 到期时间、上游额度读数、调度策略与实时状态（窗口用量、并发、冷却截止时间、额度拦截截止时间）。响应不包含任何 token，且禁止缓存。
  - `quota`：最近一次成功读取的上游额度快照，包含 5 小时与每周窗口的 `usedPercent` / `remainingPercent` / `windowSeconds` / `resetsAt`、`allowed`、credits 余额与可重置次数、`checkedAt` 与最近一次失败错误码。
  - `runtime.quotaBlockedUntil`：额度读数未过期且某个窗口已用尽时的预计恢复时间；此时新请求会被拒绝。
- 导入或重新授权：

  ```bash
  curl -b cookies.txt -X PUT http://localhost:8080/admin/api/channels/<channel-id>/keys/<key-id>/oauth-account \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"provider":"chatgpt","refreshToken":"<refresh-token>","accountEmail":"free@example.com","proxyUrl":"http://127.0.0.1:7890"}'
  ```

  - `provider` 仅支持 `chatgpt` 与 `antigravity`，且必须已配置。
  - `accessToken` 与 `accessTokenExpiresAt` 可选，但必须成对出现且未过期；留空则导入后按需刷新。
  - `proxyUrl` 只支持无认证的 `http://主机:端口`。
  - 重新授权会替换凭据，并清空账号冷却、密钥冷却与失败计数；密钥启停状态不变。
  - 同一提供方下邮箱唯一，重复绑定返回 `409 oauth_account_email_exists`。

- 浏览器登录（交互式授权）：提供方配置了授权地址与回调地址时可用，网关使用 PKCE + `state` 完成授权码交换。

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-login/start \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"provider":"chatgpt","channelId":"<channel-id>","keyId":"<key-id>","proxyUrl":"http://127.0.0.1:7890"}'
  ```

  返回一次性 `loginId`、`authorizeUrl`（10 分钟内有效）与 `redirectUri`。在浏览器打开 `authorizeUrl` 完成登录后，把浏览器最终停留的完整回调地址提交给网关：

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-login/<login-id>/complete \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"callbackUrl":"http://localhost:1455/auth/callback?code=<code>&state=<state>"}'
  ```

  - `callbackUrl` 支持完整 URL、裸查询串或 `?查询串`，最长 4096 字符；必须是浏览器登录后真实产生的地址，不要手工拼接。
  - 登录会话只存在于内存、只能使用一次；过期、重复提交或 `state` 不匹配返回 `400 oauth_login_expired` / `400 oauth_login_invalid_callback`。
  - 换取 token 失败返回 `502 oauth_login_failed`；提供方未配置授权参数返回 `400 oauth_login_unsupported`；邮箱已绑定返回 `409 oauth_account_email_exists`。
  - 成功后与手动导入一样绑定到指定密钥，并清空该账号与密钥的冷却和失败计数；响应不包含 token。

- 编辑调度策略：

  ```bash
  curl -b cookies.txt -X PUT http://localhost:8080/admin/api/oauth-accounts/<key-id>/policy \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"windowLimit":40,"windowSeconds":10800,"maxConcurrentRequests":1}'
  ```

  `windowLimit` 为 `null` 表示不限制窗口；`windowSeconds` 取值 60..604800；`maxConcurrentRequests` 取值 1..1000。

- 手动刷新额度、刷新 token 与解除冷却：
  - `POST /admin/api/oauth-accounts/<key-id>/quota/refresh`：立即读取一次上游额度并写入快照；失败保留上一次成功读数，返回 `502 oauth_quota_failed`，账号不支持或没有可用 access token 时返回 `400 oauth_quota_unsupported`。
  - `POST /admin/api/oauth-accounts/<key-id>/refresh`：允许对 `REAUTH_REQUIRED` 账号再试一次；失败返回 `502 oauth_refresh_failed`。
  - `POST /admin/api/oauth-accounts/<key-id>/cooldown/clear`：同时清除账号级冷却与密钥级候选冷却。
- 以上写操作分别写入审计动作 `oauth_account.import`、`oauth_account.login.start`、`oauth_account.login`、`oauth_account.policy.update`、`oauth_account.quota.refresh`、`oauth_account.refresh` 与 `oauth_account.cooldown.clear`，只记录渠道/密钥 id、provider、策略参数与操作结果。

---

## 三、调用方密钥生命周期与审计

### 1. 密钥创建与配额

`POST /admin/api/client-keys` 支持配置精细化限制：
- `requestsPerMinute`：按 UTC 固定分钟窗口限制单密钥每分钟请求数。
- `maxConcurrentRequests`：单密钥同时处理并发上限。
- `dailyTokenLimit`：按 UTC 自然日限制总 Token 数。
- `dailyUsdLimit` / `dailyCnyLimit` / `dailyEurLimit`：按币种独立的每日费用上限。

### 2. 密钥轮换（Rotate）与永久撤销（Revoke）

- **原子轮换**：
  ```bash
  POST /admin/api/client-keys/<id>/rotate
  ```
  立即生成新明文与哈希，旧密钥即刻失效；新明文仅在响应中返回一次。
- **永久注销**：
  ```bash
  POST /admin/api/client-keys/<id>/revoke
  ```
  永久阻断此密钥所有请求并标记撤销时间，不可恢复，避免安全隐患。

### 3. 管理操作审计日志

```bash
GET /admin/api/audit-logs?limit=50&beforeId=<cursor>
```
记录所有管理写操作（POST / PUT / PATCH / DELETE）及系统自动停用事件，包含时间戳、操作人、接口动作、目标 ID、状态与来源 IP（GET 只读操作不入审计库）。
