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

### 5. 实时占用

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
