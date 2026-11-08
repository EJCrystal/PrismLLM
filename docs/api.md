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

模型只有在**至少一条可路由凭据**（密钥或订阅账号）支持它时才会被列出并接受直连请求。渠道被停用、或该模型的密钥与账号全部停用后，模型会从列表中消失。

请求一个未列出的模型时，按原因返回不同状态：

| 场景 | 状态码 | 错误码 |
| :--- | :--- | :--- |
| 模型未配置在任何启用渠道中，或只以非对外 ID 的形式配置 | `404` | `model_not_found` |
| 模型已配置，但支持它的凭据全部停用 | `503` | `model_credentials_unavailable` |
| 模型存在，但当前协议没有可用候选（渠道不支持且无法转换） | `400` | `protocol_not_supported` |

`model_credentials_unavailable` 表示请求本身有效，凭据恢复或密钥临时停用到期后重试即可。只有存在临时停用的密钥时才会返回 `Retry-After`，取这些密钥中最早的可路由时间；密钥被探测租约占用时，重试提示同样等到租约结束。人工与永久停用不会自行恢复，因此不给出重试提示。

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

### 4. 智商检测

- 题目定义：`GET /admin/api/iq-tests/benchmarks`
- 判分器列表：`GET /admin/api/iq-tests/judges`
- 可测试目标：`GET /admin/api/iq-tests/targets`
- 运行检测：`POST /admin/api/iq-tests`
- 人工判定：`PUT /admin/api/iq-tests/<id>/manual-verdict`
- 历史与详情：`GET /admin/api/iq-tests?limit=50`、`GET /admin/api/iq-tests/<id>`

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/iq-tests \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"channelId":"<channel-id>","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"local"}'
```

`benchmark` 支持 `pelican`（鹈鹕骑行）与 `candy`（糖果测试）。可选的 `prompt` 用于替换本次提示词，
留空时使用题目内置提示词；实际使用的提示词会随记录保存，最长 20000 个字符。鹈鹕题通过渠道已保存的凭据在本地生成
SVG/HTML，再由 `judgeId` 指定的判分器判分；省略时使用 `IQ_DEFAULT_JUDGE`。内置判分器为 `local`（本地结构检查 + 人工评分）、
`manxue`（把 HTML 发送到满血AI 分类）与 `vision`（把无头浏览器逐帧截图发送到配置的多模态端点）。生成请求不写入请求日志，
也不消耗调用方密钥额度。
糖果题只要求模型返回 `{"answer": <整数>, "reason": "<推导过程>"}`，由本地规则按 `answer` 是否等于 21 判分，
不调用判分器，`judgeId` 会被忽略。鹈鹕题的最终判定顺序为人工判定 > 判分器结果 > 本地结构兜底。
结果保留最近 200 次。详细说明见 `docs/iq-test.md`。

### 5. 冷却可见性与手动解除

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

### 6. 实时占用

`GET /admin/api/occupancy` 返回当前实例内存中的在途请求数，管理台按 5 秒轮询展示：

```json
{
  "sampledAt": 1762400000000,
  "channels": [
    {
      "channelId": "ch_...",
      "concurrentRequests": 3,
      "keys": [{ "keyId": "ck_...", "concurrentRequests": 2 }]
    }
  ],
  "clientKeys": [{ "clientKeyId": "ck_...", "concurrentRequests": 1 }]
}
```

- 渠道计数是该渠道下各渠道密钥在途尝试之和；只有大于零的条目会出现在快照中。
- 渠道密钥计数覆盖所有实际发往上游的尝试，与是否配置并发上限无关；它只用于展示，不改变限流判定。
- 调用方密钥计数从请求通过鉴权与额度检查开始，到响应结束、失败或客户端断开后释放。
- 计数只保存在单实例进程内存中，进程重启或切换实例后归零，不做跨实例聚合。

### 7. 订阅账号（OAuth）

订阅账号是独立的上游凭据实体：账号拥有自己的 id、refresh/access token、代理、优先级、模型授权、调度策略与额度，**不再依附于任何渠道密钥**。

订阅凭据只能用于提供方自己的官方端点，因此端点由代码常量固定：网关为每个已实现上游协议的 provider 置备一条 kind=provider 的系统渠道，账号导入后自动关联到它，用户不再手工选择渠道。这类渠道不可编辑、不可删除，也不出现在渠道列表里。详细语义见 ADR 0016。

- 查看可用的 OAuth 提供方：`GET /admin/api/oauth-providers`，返回 `id`、`label`、`configured`、`loginSupported` 与 `managedChannel`。`configured` 表示网关已注册该提供方的 token 刷新器；`chatgpt` 与 `antigravity` 都内置公开客户端参数，默认即为 `true`，不需要部署方预先申请。两者也都内置授权端点与回调地址，可以直接发起浏览器登录；`managedChannel=true` 表示账号的端点渠道由系统维护。
- 维护官方模型列表：`PUT /admin/api/oauth-providers/{provider}/models`，请求体 `{"models":["..."]}`；整体替换系统端点渠道的模型映射，用于官方模型变更。
- 从上游刷新模型目录：`POST /admin/api/oauth-providers/{provider}/models/refresh`；用该提供方第一个账号读取官方模型列表（Antigravity 走 Code Assist 的 fetchAvailableModels），写回系统渠道并返回 `{"models":[{"id","name"}]}`。
- 账号看板：`GET /admin/api/oauth-accounts`，返回账号 `id` / `name`、provider、授权状态、是否启用、优先级、授权模型、关联渠道（`channelIds` 与 `channels[{id,name}]`）、token 到期时间、上游额度读数、调度策略与实时状态。响应不包含任何 token，且禁止缓存。
  - `models` 为空表示继承所关联渠道的全部模型。
  - `authState=REAUTH_REQUIRED` 的账号仍保留为候选，但凭据解析会返回 `subscription_account_unavailable` 并继续尝试其他候选；不会把 refresh token 发给上游。
  - `quota`：最近一次成功读取的上游额度快照，包含 5 小时与每周窗口的 `usedPercent` / `remainingPercent` / `windowSeconds` / `resetsAt`、`allowed`、credits 余额与可重置次数、`checkedAt` 与最近一次失败错误码。
  - `runtime.quotaBlockedUntil`：额度读数未过期且某个窗口已用尽时的预计恢复时间；此时新请求会被拒绝。
- 新建账号（不需要先建渠道或密钥）：

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-accounts \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"name":"Free 账号","provider":"chatgpt","refreshToken":"<refresh-token>","accountEmail":"free@example.com","channelIds":["<channel-id>"],"priority":1,"proxyUrl":"http://127.0.0.1:7890"}'
  ```

  - `provider` 仅支持 `chatgpt` 与 `antigravity`；两者的客户端参数都内置，直接可导入。
  - `channelIds` 已废弃：`managedChannel=true` 的提供方会忽略该字段，导入后自动关联系统端点渠道；只有上游协议尚未实现的提供方（当前是 `antigravity`）仍按原方式手工关联。
  - 渠道本身不再必须持有静态密钥：可以创建渠道后只关联订阅账号，数据面会把账号作为该渠道路由凭据。
  - `accessToken` 与 `accessTokenExpiresAt` 可选，但必须成对出现且未过期；留空则导入后按需刷新。
  - `priority` 取值 1..1000，数值越小越优先；只影响同渠道内账号之间的排序。
  - `proxyUrl` 只支持无认证的 `http://主机:端口`；目标主机默认为本机回环地址，其他主机需要服务端配置 `OAUTH_PROXY_ALLOWED_HOSTS`，否则返回 `400 invalid_oauth_proxy`。
  - 同一提供方下邮箱唯一，重复创建返回 `409 oauth_account_email_exists`。

- 重新授权（替换凭据）：`PUT /admin/api/oauth-accounts/<account-id>`，请求体与新建一致。只替换 refresh/access token，不改启停状态；成功后清空账号级冷却与各关联渠道下的候选冷却。留空 refresh token 表示保留现有长期凭据。
- 修改账号设置：`PUT /admin/api/oauth-accounts/<account-id>/settings`，请求体 `{name, priority, enabled, proxyUrl, clearProxy}`；`enabled=false` 后账号不再参与路由。
- 调整关联渠道：`PUT /admin/api/oauth-accounts/<account-id>/channels`，请求体 `{"channelIds":["..."]}`；整体替换关联集合。`managedChannel=true` 的提供方返回 `400 oauth_account_channel_managed`，关联由系统维护。
- 调整模型授权：`PUT /admin/api/oauth-accounts/<account-id>/models`，请求体 `{"models":["..."]}`；传空数组表示继承所关联渠道的全部模型。
- 删除账号：`DELETE /admin/api/oauth-accounts/<account-id>`；token、额度与调度状态一并删除，不影响渠道或静态密钥。

- 浏览器登录（交互式授权）：提供方配置了授权地址与回调地址时可用，网关使用 PKCE + `state` 完成授权码交换。

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-login/start \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"provider":"chatgpt","name":"新账号","channelIds":["<channel-id>"],"proxyUrl":"http://127.0.0.1:7890"}'
  ```

  返回一次性 `loginId`、`authorizeUrl`（10 分钟内有效）与 `redirectUri`。在浏览器打开 `authorizeUrl` 完成登录后，把浏览器最终停留的完整回调地址提交给网关：

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-login/<login-id>/complete \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"callbackUrl":"http://localhost:1455/auth/callback?code=<code>&state=<state>"}'
  ```

  - 新建账号时不要传 `accountId`；对已有账号重新授权时传 `accountId`，可附带新的 `name` 与 `channelIds`。
  - `callbackUrl` 支持完整 URL、裸查询串或 `?查询串`，最长 4096 字符；必须是浏览器登录后真实产生的地址，不要手工拼接。
  - 登录会话只存在于内存，10 分钟有效；授权码只能交换一次，绑定成功后会话立即失效。过期或重复提交返回 `400 oauth_login_expired`，`state` 不匹配返回 `400 oauth_login_invalid_callback`。
  - 重新授权期间目标账号被删除或替换凭据时返回 `409 oauth_login_stale`，不会把授权结果写到新目标上。
  - 换取 token 失败返回 `502 oauth_login_failed`；提供方未配置授权参数返回 `400 oauth_login_unsupported`；邮箱已存在返回 `409 oauth_account_email_exists`，删除原账号后可用同一 `loginId` 重试，不会再次请求 token 端点。
  - 成功后账号立即写入关联渠道并清空冷却；响应不包含 token。

- 编辑调度策略：

  ```bash
  curl -b cookies.txt -X PUT http://localhost:8080/admin/api/oauth-accounts/<account-id>/policy \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"windowLimit":40,"windowSeconds":10800,"maxConcurrentRequests":1}'
  ```

  `windowLimit` 为 `null` 表示不限制窗口；`windowSeconds` 取值 60..604800；`maxConcurrentRequests` 取值 1..1000。

- 手动刷新额度、刷新 token 与解除冷却：
  - `POST /admin/api/oauth-accounts/<account-id>/quota/refresh`：立即读取一次上游额度并写入快照；失败保留上一次成功读数，返回 `502 oauth_quota_failed`，账号不支持或没有可用 access token 时返回 `400 oauth_quota_unsupported`。
  - `POST /admin/api/oauth-accounts/<account-id>/refresh`：允许对 `REAUTH_REQUIRED` 账号再试一次；失败返回 `502 oauth_refresh_failed`。
  - `POST /admin/api/oauth-accounts/<account-id>/cooldown/clear`：清除账号级冷却，并清理该账号在所有关联渠道下的候选冷却。
- 以上写操作分别写入审计动作 `oauth_account.import`、`oauth_account.delete`、`oauth_account.settings.update`、`oauth_account.login.start`、`oauth_account.login`、`oauth_account.policy.update`、`oauth_account.quota.refresh`、`oauth_account.refresh` 与 `oauth_account.cooldown.clear`，只记录账号 id、provider、策略参数、渠道 id 与操作结果。

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
