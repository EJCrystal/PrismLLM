# llm-gateway

面向内部团队的 LLM 网关，支持 OpenAI Chat Completions、OpenAI Responses 与 Anthropic Messages 的原生透传和跨协议转换。网关按渠道授权与对外模型 ID 路由，同协议优先，必要时按渠道协议顺序转换，并记录用量与请求元数据。

## 能力边界

- 协议入口 `/v1/chat/completions`、`/v1/responses` 与 `/v1/messages`，支持流式和非流式请求，并可在三种协议间互相转换。
- 渠道按有序的 `chat_completions`、`responses`、`messages` 声明支持协议；同协议候选优先，原生候选不可用后按该顺序选择转换目标。
- 渠道显式授权上游模型；可配置「对外模型 ID」，留空时沿用上游模型名。渠道启用即参与直连暴露。
- 渠道支持密钥池：每个密钥独立保存凭据、启用状态、优先级和可用模型；同一渠道内按密钥优先级分档，同档密钥轮询。
- 上游密钥支持可配置的自动禁用：规则按状态码、错误文本、连续/窗口计数匹配，动作区分临时停用与永久停用；命中规则与停用状态写入操作审计。
- 自定义对外模型 ID 会替代上游名对外暴露；同一渠道的多个上游模型可共享一个对外模型 ID，多个渠道配置相同 ID 时也会自动聚合并参与候选分摊。
- 调用方密钥鉴权，支持按密钥限制每分钟请求数和并发数；上游凭据使用 AES-256-GCM 加密存储。
- 流式响应不缓冲、客户端断开向上游传播。
- 管理 API 与 Vue 3 管理界面：
  - 渠道管理：接入点、协议、密钥池、对外模型 ID 映射及模型计费单价配置。
  - 多主题支持：浅色（Light）、深色（Dark）、护眼暖色（Sepia）三主题无缝轮转与持久化。
  - 模型列表：查看 `/v1/models` 实际对外暴露结果与每日缓存的 models.dev 目录对比。
  - 调用方密钥轮换与永久撤销、管理操作审计和请求日志。
- PostgreSQL + Flyway；单实例部署。

明确不做：Gemini 原生入口、多租户 RBAC、语义缓存、MCP、多副本。协议转换第一版不支持图片、文件、音频、Provider 内置工具和 Responses 有状态续接。

## 架构

```
client --client key--> gateway --configured auth--> protocol-compatible upstream
                                |
                                +-- PostgreSQL: channels, channel models, client keys, model prices, request logs, admin audit logs
```

数据面：`/v1/chat/completions`、`/v1/responses`、`/v1/messages`、`/v1/models`。
管理面：`/admin/api/*`，由会话 Cookie + CSRF Token 保护。
运维面：`/health/live`、`/health/ready`、`/metrics`。

渠道的 `baseUrl` 必须包含上游要求的版本路径，例如 `https://api.openai.com/v1` 或 `https://api.anthropic.com/v1`。网关按请求协议追加 `/chat/completions`、`/responses` 或 `/messages`，不会自动补 `/v1`。Anthropic Messages 请求可使用 `Authorization: Bearer <gateway-key>` 或 `x-api-key: <gateway-key>`；渠道上游鉴权可选择 Bearer 或 Anthropic `x-api-key`，`anthropic-version` 缺省为 `2023-06-01`。

启用渠道的对外模型 ID 会出现在 `/v1/models` 并可直接路由。满足条件后：
- **未配置对外模型 ID（留空）**：对外使用上游模型名；
- **配置了对外模型 ID**：对外只显示该 ID，调用时网关会将它重写为实际上游模型名。
单独配置的模型别名不依赖渠道直连暴露，但别名候选要求其渠道处于启用状态。

## 快速开始

```bash
cp .env.example .env
# 修改 .env 中的 GATEWAY_MASTER_KEY、ADMIN_PASSWORD
docker compose up -d --build
```

管理界面默认在 `http://localhost:8080/admin/`。首次启动会用 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 创建管理员；之后点击右上角管理员头像，可修改头像和密码。修改密码会让所有管理会话失效，需要重新登录。

本地不使用 Docker 时，先构建管理界面再打包后端：

```bash
npm --prefix web install
npm --prefix web run build
mvn package
java -jar target/llm-gateway-0.1.0-SNAPSHOT.jar
```

本地开发前端：

```bash
cd web
npm install
npm run dev
```

Vite 会把 `/admin/api` 代理到 `http://localhost:8080`，开发地址是 `http://localhost:5173/admin/`。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | HTTP 端口 |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/llm_gateway` | JDBC URL |
| `DATABASE_USER` | `llm_gateway` | 数据库用户 |
| `DATABASE_PASSWORD` | `llm_gateway` | 数据库密码 |
| `GATEWAY_MASTER_KEY` | 无 | Base64 编码的 32 字节 AES 密钥，必填 |
| `ADMIN_USERNAME` | `admin` | 首次启动创建的管理员用户名 |
| `ADMIN_PASSWORD` | 无 | 首次启动创建的管理员密码，必填；后续密码修改以数据库为准 |
| `CORS_ORIGINS` | 空 | 允许的浏览器来源，逗号分隔 |
| `REQUEST_BODY_LIMIT_BYTES` | `10485760` | 请求体上限 |
| `CONNECT_TIMEOUT_MS` | `5000` | 上游连接超时 |
| `FIRST_TOKEN_TIMEOUT_MS` | `120000` | 流式上游首 token 超时 |
| `IDLE_TIMEOUT_MS` | `60000` | 流空闲超时 |
| `TOTAL_TIMEOUT_MS` | `600000` | 单请求总超时 |
| `MAX_ATTEMPTS` | `2` | 首 token 前最多尝试的候选数 |
| `COOLDOWN_TRANSIENT_BASE_MS` | `30000` | 瞬时错误（连接失败、超时、空流、5xx）冷却起步时长 |
| `COOLDOWN_TRANSIENT_MAX_MS` | `600000` | 瞬时错误冷却上限（连续失败翻倍递增） |
| `COOLDOWN_RATE_LIMIT_DEFAULT_MS` | `30000` | 429 无 `Retry-After` 时的默认冷却时长 |
| `COOLDOWN_RATE_LIMIT_MAX_MS` | `60000` | 429 冷却时长上限（遵从 `Retry-After` 并封顶） |
| `COOLDOWN_AUTH_FAILURE_MS` | `600000` | 401/403 密钥级冷却时长；达到自动禁用阈值后不再路由到该密钥 |
| `KEY_AUTO_RECOVERY_INTERVAL_MS` | `30000` | 临时停用到期清理间隔，最小 1000、最大 3600000 毫秒 |
| `TTFT_SLOW_THRESHOLD_MS` | `10000` | 流式请求首 token 软阈值 |
| `TTFT_SLOW_STREAK` | `3` | 首 token 连续超过软阈值多少次后触发候选冷却 |
| `DRAIN_TIMEOUT_MS` | `30000` | 优雅停机 drain 时间 |
| `STATIC_DIR` | `web/dist` | 管理界面静态目录 |
| `COOKIE_SECURE` | `false` | 管理面 Cookie 是否只走 HTTPS；生产应为 `true` |

生成 master key：

```bash
openssl rand -base64 32
```

## API

调用：

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{"model":"fast-chat","stream":true,"messages":[{"role":"user","content":"hi"}]}'
```

Responses 调用：

```bash
curl http://localhost:8080/v1/responses \
  -H "Authorization: Bearer sk-gw-..."   -H "Content-Type: application/json"   -d '{"model":"fast-chat","stream":true,"input":"hi"}'
```

Anthropic Messages 调用：

```bash
curl http://localhost:8080/v1/messages \
  -H "x-api-key: sk-gw-..." \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-fast","max_tokens":128,"messages":[{"role":"user","content":"hi"}]}'
```

模型列表：`GET /v1/models` 返回当前启用的别名与启用渠道的可直连模型 ID。请求日志会记录入站协议和实际出站协议。

管理 API 示例：

```bash
curl -c cookies.txt -X POST http://localhost:8080/admin/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"change-me"}'
```

写操作需要把登录响应里的 `gw_csrf` Cookie 值放到 `X-CSRF-Token` 请求头。

拉取某个渠道的上游模型列表（网关会请求 `{baseUrl}/models`）。未指定 `keyId` 时使用优先级最高的启用密钥：

```bash
curl -b cookies.txt 'http://localhost:8080/admin/api/channels/<channel-id>/models?keyId=<key-id>'
```

返回 `{"object":"list","data":[{"id":"...","ownedBy":"...","authorized":true}]}`。`authorized` 表示该模型是否已被选中的密钥授权。上游不支持 `/models` 时返回 502，并带上 `upstream_models_error`。

新建渠道时可在保存前发现上游模型：

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/model-discovery \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"baseUrl":"https://api.openai.com/v1","credential":"sk-..."}'
```

编辑渠道时可只传 `channelId` 和可选的 `keyId`，复用已保存密钥。保存渠道时通过 `keys` 数组提交密钥池；每个密钥包含 `name`、`credential`、`state`（`ACTIVE` 正常，`MANUAL_DISABLED` 人工停用；默认 `ACTIVE`）、`priority`、`models` 和可选的 `rateLimit`。请求与响应彻底使用 `state` 枚举替换原 `enabled` 布尔字段，不再接受 `enabled`；客户端提交 `AUTO_*` 自动状态会被拒绝（自动状态只由系统维护）。旧版单凭据字段仍兼容：新建时自动生成“默认密钥”，更新时只作用于唯一的默认密钥并保留已有 `rateLimit`。取消仍被别名候选引用的模型授权会返回 `409 channel_model_in_use`。

渠道密钥池示例：

```json
{
  "name": "openai-primary",
  "baseUrl": "https://api.openai.com/v1",
  "authType": "bearer",
  "protocols": ["chat_completions"],
  "enabled": true,
  "modelMappings": [
    {"upstreamModel": "gpt-4o-mini"},
    {"upstreamModel": "deepseek-chat"}
  ],
  "keys": [
    {
      "name": "primary",
      "credential": "sk-primary",
      "state": "ACTIVE",
      "priority": 1,
      "models": ["gpt-4o-mini"],
      "rateLimit": {"enabled": true, "requestsPerMinute": 60, "maxConcurrentRequests": 3}
    },
    {"name": "backup", "credential": "sk-backup", "state": "ACTIVE", "priority": 2, "models": ["gpt-4o-mini", "deepseek-chat"]}
  ]
}
```

### 上游分组主动限流

每个渠道密钥代表一个上游分组，可在密钥卡片中单独配置 `requestsPerMinute` 和 `maxConcurrentRequests`。两项都按 `channelId + keyId` 共享计数，覆盖该密钥下的全部模型；未配置或 `enabled=false` 时不限流。只填写其中一项时，另一项保持不限流。

RPM 使用平滑令牌桶，每个实际上游请求尝试消耗一个令牌；最大并发从发送上游请求开始占用，并在成功、失败、超时或取消后释放。本地限流拒绝会优先尝试其他密钥；所有候选都被本地限流时返回 `429 upstream_key_rate_limit_exceeded` 和 `Retry-After`，不会触发候选冷却或自动禁用统计。限流状态保存在单实例内存中，规则保存在 PostgreSQL 的 `channel_key_rate_limits` 表。

渠道是否参与直连路由和 `/v1/models` 只由启用状态决定；渠道级 `exposeUpstreamModels` 开关与全局 `EXPOSE_UPSTREAM_MODELS` 环境变量均已移除。

渠道模型映射通过 `modelMappings` 提交：

```json
{
  "modelMappings": [
    {"upstreamModel": "Qwen3.8-Flash", "canonicalModelId": "qwen3.8-flash", "mappingSource": "models_dev"}
  ]
}
```

`models` 字段仍可用于只提交上游模型列表；更新时会保留这些模型已有的规范 ID。`mappingSource` 支持 `manual` 和 `models_dev`。同一渠道内的多个上游模型可以映射到同一个对外模型 ID，网关会将这些上游名作为同一模型的候选；如果它们实际不是同一模型，应拆分对外 ID。

### 上游密钥自动禁用

管理台的「上游接入点 → 密钥自动禁用」可修改全局规则。规则按顺序匹配，首条命中生效；每条规则包含状态码、状态码下限、错误文本正则、连续/窗口计数、动作（临时/永久）和临时停用时长。默认规则：401/403 且命中 `invalid_api_key`、`authentication_error`、`invalid_grant` 等稳定特征 → 连续 2 次永久停用；401/403 账号停用或结算类 → 1 次永久停用；其余 401/403 → 5 分钟内 3 次临时停用 30 分钟；429/5xx/连接失败/超时 → 5 分钟内 8 次临时停用 10 分钟；400/404/422 不命中任何规则。成功只清零连续计数，窗口计数保留；客户端取消、网关本地限流和请求体错误不提交结果；首 token 之后才出现的流异常也不参与密钥禁用。冷却规则照常运行，但已停用的 key 不参与 fail-open。

规则、按规则分桶的失败计数和停用状态都保存在 PostgreSQL，重启后仍有效；修改规则从零开始计数，关闭自动禁用后已有停用状态保持。并发请求按 key 串行结算，只有第一次达到阈值会停用并写入一条系统审计；审计包含渠道/密钥名称、命中规则、计数、动作、状态、到期时间及对应请求 ID，不含凭据或请求正文。更换凭据或手动切换启用状态会清空该 key 的失败计数与密钥级冷却。

密钥状态包含四态：`ACTIVE`（正常）、`MANUAL_DISABLED`（人工停用）、`AUTO_TEMPORARY`（自动临时停用）和 `AUTO_PERMANENT`（自动永久停用）。自动状态只由系统在达到规则阈值时维护，客户端只能提交人工状态（`ACTIVE` 或 `MANUAL_DISABLED`）。临时停用（`AUTO_TEMPORARY`）带到期时间 `autoDisableUntil`，到期即自动视作可用恢复路由；到期后通过 `probe_until` 原子抢占单探测租约，并发请求不会同时打上游，探测成功恢复、失败按命中规则重新计时。后台任务按 `KEY_AUTO_RECOVERY_INTERVAL_MS` 收尾已到期且没有有效租约的临时停用。人工停用与永久停用永不自动放行。**因凭据失效被永久停用的密钥，重新启用前必须更换上游凭据**；从临时停用或人工停用启用无需更换凭据。编辑请求可传返回的 `stateVersion`，状态发生变化时返回 `409 channel_key_state_changed`，避免旧页面覆盖自动禁用。

管理 API：`GET/PUT /admin/api/key-auto-disable-settings`，写入仍需管理员会话与 CSRF。请求体为 `enabled` 与 `rules` 数组；旧客户端仍可提交 `authFailureThreshold`、`failureThreshold`、`failureWindowMinutes`，后端会翻译成两条兼容规则。规则字段包括 `id`、`name`、`statusCodes`、`statusCodeAtLeast`、`bodyPatterns`（RE2 正则）、`threshold`、`windowMinutes`、`streak`、`action`（`TEMPORARY` / `PERMANENT`）、`disableMinutes` 和 `includeTransportFailures`。例如：

```json
{"enabled":true,"rules":[{"id":"credential_invalid","name":"凭据失效","statusCodes":[401,403],"bodyPatterns":["invalid[\\s_-]*(x-)?api[\\s_-]?key"],"threshold":2,"windowMinutes":5,"streak":true,"action":"PERMANENT"}]}
```

共享同一对外 ID 时，路由先按渠道优先级分档；同一渠道内先按上游模型名稳定排序，首个名称作为主入口，只有失败、冷却或不可用时才回退到同渠道的其他名称。每个上游模型下再按密钥优先级分档，同档密钥轮询。这样既避免把同一模型的多个上游别名误当成独立容量，也能把同一模型在多个密钥上的容量聚合起来。

查看调用方通过 `/v1/models` 获取到的对外结果：

```bash
curl -b cookies.txt http://localhost:8080/admin/api/exposed-models
```

返回 `object` 和 `data`，每条记录包含 `id`、`source`（`alias` / `upstream` / `canonical`）与 `ownedBy`。配置了对外 canonical ID 的上游模型会标记为 `canonical`。管理界面的“模型列表”菜单展示同一结果，并提供原始 JSON 响应预览。

同一页面还会加载 models.dev 目录，展示模型 ID、`canonical_model_id`、模型开发者和服务提供方，并在对外模型表中标记匹配结果。目录接口：`GET /admin/api/models-dev`。目录在后台缓存 24 小时，并在到期前 1 小时刷新；刷新失败时继续使用上一份完整快照并每分钟重试。上游 `/models` 返回的 `owned_by` 只表示上游元数据，不作为模型开发者；例如 Qwen 通过 OpenAI 兼容接口返回 `owned_by=openai` 时，仍按 canonical ID 的 `alibaba` 命名空间识别开发者。

渠道编辑弹窗支持手动填写对外模型 ID，也可点击上游模型名或箭头将原名快速填入。模型列表页展示实际对外 ID、来源、上游模型和渠道，并提供 models.dev 目录用于查询与对照；目录信息不会自动修改渠道配置。

命名建议：可参考 models.dev 的 `canonical_model_id` 填写对外模型 ID；调用方使用该 ID 时，网关会路由到对应的上游原始模型名。配置对外模型 ID 会替代上游名作为直连入口；渠道停用时其所有直连 ID 停止路由。

探测渠道支持的协议：

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/probe-protocols \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini"}'
```

返回每个协议的 `supported`、`statusCode` 和说明。探测会使用已保存凭据发送最小请求（Chat `max_tokens=1`、Responses `max_output_tokens=1`、Messages `max_tokens=1`），可能产生少量 token 费用；不传 `model` 时使用渠道已授权模型中的第一个。

渠道编辑弹窗的授权模型列表提供「测试」入口，可对指定模型发送一次真实请求，并指定协议和测试提示词。测试使用已保存的渠道凭据，不经过数据面、不写入请求日志，也不消耗调用方密钥额度；最多输出 128 token，超时 45 秒。连通性测试不指定推理强度。

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/model-test \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","protocol":"chat_completions","prompt":"请只回复 OK"}'
```

渠道页「上游接入点」标题右侧的「全局测试配置」可设置默认提示词，对应 `GET/PUT /admin/api/model-test-settings`；单次测试可以临时修改提示词。旧请求里的推理强度字段会被忽略。

### 候选档位、分摊与候选冷却

别名候选的 `position` 是优先级档位：同档候选轮询分摊流量（例如几个有优惠的渠道共扛同一模型），后续档位只在前面档位失败或冷却时启用。创建/更新别名时每个候选可显式传 `position`（0..999，同档填同一个数），省略时按数组顺序递增；直连暴露的候选同处第 0 档，天然参与分摊。

上游在首 token 前发生连接失败、超时、429、5xx、401、403 或 404 时，按档位顺序尝试下一个候选，最多执行 `MAX_ATTEMPTS` 次实际上游请求。400、422 等请求参数错误直接返回；首 token 已提交后的流中断不切换渠道。候选之间不做退避等待——下一个候选是不同的上游，立即切换。

失败按影响面记录候选冷却：传输层错误（连接失败、超时、空流、流中断）和 5xx 记在渠道键上；401/403 记在密钥键上；429 与首 token 过慢记在密钥加模型候选键上。瞬时错误从 `COOLDOWN_TRANSIENT_BASE_MS` 起步、同键连续失败翻倍、封顶 `COOLDOWN_TRANSIENT_MAX_MS`；429 遵从数字 `Retry-After` 并封顶 `COOLDOWN_RATE_LIMIT_MAX_MS`；401/403 冷却 `COOLDOWN_AUTH_FAILURE_MS` 并打 ERROR 日志，达到密钥自动禁用规则时停用具体密钥；404 不冷却。流式请求首 token 连续 `TTFT_SLOW_STREAK` 次超过 `TTFT_SLOW_THRESHOLD_MS` 视同瞬时错误冷却该候选，被计数的请求本身照常完成，非流式不计数。

冷却后置且独立于自动禁用：路由优先尝试未冷却候选，冷却到期后放行单个探测请求（探测成功完全恢复，失败翻倍重新冷却）；所有已启用候选都在冷却中时仍按档位顺序照常尝试（fail-open），自动禁用的密钥不会参与。客户端主动断开记录为 `client_disconnect`，不奖不罚。冷却状态内存为准、状态变更异步写透 PostgreSQL 并在启动时恢复；请求日志的 `upstreamAttempts` 记录实际调用的上游次数，`gateway_upstream_cooldown_started_total{channel,reason}` 与 `gateway_upstream_cooldown_skips_total{channel}` 提供观测。

失败请求会在日志中记录 `failureStage`（`request` / `routing` / `upstream` / `streaming` / `client`）、`errorMessage` 和上游返回的 `upstreamErrorCode`。 请求日志同时展示输入、输出、推理和总 token、输出速率（输出 token / 总耗时）与缓存命中率（`cached_tokens / input_tokens`），并记录调用方密钥名称和请求中的推理强度；上游未返回的字段显示“—”，不做估算。渠道列显示请求发生时的渠道名称快照，同时保留 `channelId` 用于精确关联；渠道改名不会改写历史日志。请求日志列表只显示错误码和阶段，完整原因在“详情”弹层中查看；上游响应正文仍然不落库。

请求日志接口 `GET /admin/api/request-logs?limit=50&beforeId=<id>` 使用只读的日志 `id` 作为游标，按 ID 倒序分页。管理界面与操作审计共用游标分页器，每页可选择 25、50 或 100 条；切换条数会丢弃旧游标并重新加载第一页，点击“加载更早记录”后继续读取，不会一次加载全部历史数据。

请求日志搜索只作用于当前已加载记录，匹配请求 ID、Trace ID、模型、渠道、协议、HTTP 状态码、错误码、错误信息、失败阶段、推理强度和调用方密钥名称；例如输入 `404` 会匹配 HTTP 状态为 404 的记录。

### 调用方密钥额度与限流

调用方密钥可分别配置每日 token 上限、每日费用上限、每分钟请求数和最大并发请求数。留空表示该维度不限制；请求速率 `0` 表示暂停新请求。每日额度按 UTC 自然日重置，每分钟请求数按 UTC 固定分钟窗口计算，所有金额配置最多 8 位小数。

费用上限按模型单价的实际币种分别配置，目前支持 USD、CNY 和 EUR。某个币种未配置上限时，该币种的费用不参与额度限制；不同币种之间不做隐含汇率换算。日费用额度只有在对应渠道和模型已配置单价且响应包含用量时才能累计；缺失用量不会按 0 计费。

并发上限是单网关进程内的并发请求数。每个请求在通过密钥速率、每日额度和并发检查后占用相应名额；响应完成、流式输出结束、路由失败或客户端中断时都会释放。超额请求返回 HTTP `429`，并通过 OpenAI 兼容错误信封给出以下错误码：

- `daily_token_quota_exceeded`：今日 token 额度已用完。
- `daily_cost_quota_exceeded`：某个已配置币种的今日费用额度已用完。
- `concurrency_limit_exceeded`：单密钥并发请求数已达上限。
- `key_rate_limit_exceeded`：单密钥每分钟请求数已达上限。

管理界面和 `GET/POST /admin/api/client-keys`、`PUT /admin/api/client-keys/{id}` 支持以下可选字段：`dailyTokenLimit`、`dailyUsdLimit`、`dailyCnyLimit`、`dailyEurLimit`、`maxConcurrentRequests`、`requestsPerMinute`。查询密钥时返回 `quotaUsage`，包含今日 token、各币种费用、当前并发数和当前分钟请求数。每日用量保存在 PostgreSQL，账号删除或新一天开始时重新计算。

### 密钥轮换、永久撤销与管理操作审计

`POST /admin/api/client-keys/{id}/rotate` 会为现有密钥生成新的明文、哈希和展示前缀，并原子替换旧哈希。记录 ID、过期时间和全部额度配置保持不变，旧密钥立即失效；新明文只在本次响应返回一次，并带 `Cache-Control: no-store`。已撤销密钥不能轮换。

`POST /admin/api/client-keys/{id}/revoke` 将密钥永久标记为撤销并停用。重复调用保持原撤销时间，便于安全重试。撤销后的密钥不能重新启用、编辑或轮换，数据面请求直接返回 HTTP `401`。普通停用仍可通过 `PUT /admin/api/client-keys/{id}` 恢复，二者在接口、管理界面和 `revokedAt` 字段中明确区分。

管理面的所有写操作（包括登录、渠道和别名配置、单价修改、日志清理及密钥生命周期操作）都会写入 `admin_audit_logs`；系统自动禁用上游密钥也会写入同一表。记录包含操作者、动作、HTTP 方法、路径、结果、状态码、`X-Request-Id`、来源地址、错误码及可选的规则/禁用原因元数据，不包含请求体、prompt、响应正文、CSRF、Cookie、密钥明文或哈希。请求未提供有效 `X-Request-Id` 时，网关生成一个 UUID，并在响应中返回。

审计记录接口为 `GET /admin/api/audit-logs?limit=100&beforeId=<id>`，按 ID 倒序分页，最多返回 500 条。接口只读，不提供修改或删除操作；管理界面“操作审计”页可直接查看。`sourceIp` 取当前连接的对端地址；部署在反向代理后时通常显示代理地址，网关不信任可伪造的转发头。

### 用量报表与日志保留

管理界面的“用量报表”按时间范围汇总请求数、错误数、输入/输出/总 token，以及按实际计费币种分别累计的费用；同时提供按调用方密钥的明细。

报表接口：`GET /admin/api/usage-report?from=<epoch-ms>&to=<epoch-ms>`，可选 `clientKeyId` 过滤。默认最近 7 天，单次查询范围最多 366 天。费用不做跨币种换算。

请求日志保留策略由以下环境变量控制：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `REQUEST_LOG_RETENTION_DAYS` | `30` | 请求日志保留天数，范围 1..3650 |
| `REQUEST_LOG_CLEANUP_ENABLED` | `true` | 是否启用后台定时清理 |
| `REQUEST_LOG_CLEANUP_INTERVAL_MS` | `3600000` | 自动清理间隔，最小 60000 毫秒 |
| `REQUEST_LOG_CLEANUP_BATCH_SIZE` | `5000` | 每批删除数量，范围 100..100000 |

清理任务只删除超过保留期的 `request_logs`，按批次执行，避免大事务影响数据面。每日额度账本 `client_key_quota_usage` 不由日志清理任务删除。保留策略可通过 `GET /admin/api/request-log-retention` 查看，通过 `PUT /admin/api/request-log-retention` 开关自动清理；关闭时会取消定时任务，手动清理接口（`POST /admin/api/request-log-retention/cleanup`）仍可独立执行。管理面保存的开关会覆盖启动环境变量并在重启后保留。

### 单请求计费

管理界面的“渠道”列表提供便捷的「配置模型单价」入口，直接为每个渠道已授权的上游模型配置输入、输出与缓存读取单价（每百万 token 单价），在请求日志中实时计算并显示单条请求费用。渠道列表还会显示每个模型的价格来源：渠道价、默认价或未定价。

多模型渠道可以依次切换不同模型分别填写，各模型的未保存草稿会独立保留，最后通过「保存全部」一次性提交。已配置的渠道覆盖价可以删除，删除后恢复为继承模型默认价；没有默认价时恢复为未定价。

价格分为两层，解析优先级为 `渠道 + 上游模型` > `模型默认价` > 未定价：

- **渠道覆盖价**：为单个渠道的上游模型配置，优先于默认价；接口为 `GET/POST /admin/api/model-prices`、`PUT/DELETE /admin/api/model-prices/{id}`。
- **模型默认价**：按对外模型 ID 配置，渠道没有覆盖价时自动继承；接口为 `GET/POST /admin/api/model-default-prices`、`PUT/DELETE /admin/api/model-default-prices/{id}`。
- **批量接口兼容**：`POST /admin/api/model-prices/batch` 仍保留给已有 API 调用方；管理台不再提供“批量套用”入口。

模型默认价的模型 ID 优先使用渠道配置的规范 ID（canonical model ID），未配置时回退到上游模型名；规范 ID 没有对应默认价时也会再尝试上游模型名。这样同一对外模型跨渠道只需要维护一份默认价。

“获取参考单价”返回的原始价格为 USD。切换到 CNY 或 EUR 时，管理面始终以保留的 USD 原值为基准换算，并在连续切换币种时显示 `1 USD = …` 的参考汇率、日期和来源；手动修改价格后，后续切换改为按当前币种换算。汇率缓存 6 小时，获取失败时会恢复原币种而不是保留错误数值。

费用在请求日志异步写入时计算并快照，后续改价不会改写历史记录：

```text
费用 = (输入 token - 缓存 token) × 输入单价
     + 缓存 token × 缓存输入单价
     + 输出 token × 输出单价
     以上各项均除以 1,000,000，保留 8 位小数
```

- 缓存输入单价留空时按输入单价计费。
- 推理 token 已包含在输出 token 中，不重复计费。
- 未配置价格或 usage 缺失时，费用显示“未配置”或“—”，不会按 0 处理。

## 安全边界

- 调用方密钥只保存 SHA-256 哈希和前缀，创建时明文只返回一次。
- 密钥轮换后旧哈希立即失效；永久撤销不可通过编辑接口恢复。
- 上游凭据使用 AES-256-GCM 加密存储，密钥来自 `GATEWAY_MASTER_KEY`；接口只返回“已配置”，不回显。
- 管理面读取要求有效 HttpOnly 会话 Cookie；写操作还要求 SameSite=Strict Cookie 和 `X-CSRF-Token`。调用方 Bearer 密钥不能访问管理 API。
- 当前只有一个管理员角色，没有按团队划分的 RBAC；所有有效管理会话拥有相同配置权限。
- 管理操作审计是只追加的元数据记录，不含请求体或凭据。默认不记录 prompt/响应正文；日志中不出现任何凭据。
- 管理界面与指标端点应只在内网暴露。

## 可观测性与告警

`/metrics` 暴露请求结果、完整请求耗时、密钥速率与并发拒绝、管理审计写入失败、上游尝试成功率和上游错误分类。模板 Prometheus 配置与告警规则位于 `monitoring/prometheus/`，覆盖服务失联、readiness 失败、错误率、p95 延迟、额度拒绝、管理审计写入失败和单渠道上游健康；接入说明见 `monitoring/README.md`。

数据面接受 W3C `traceparent`。有效请求头中的 trace ID 会保留，网关生成新的 span ID 并向上游透传；响应返回 `traceparent` 与 `X-Trace-Id`，请求日志保存 trace ID，可在管理界面搜索和复制。

## 测试

GitHub Actions 的 `CI` 工作流会在拉取请求和推送到 `main` 时运行前端测试、前端生产构建及后端测试。它只具有仓库读取权限，并使用独立的并发组；同一 PR 或分支的新提交会取消尚未完成的旧运行。

### 后端自动化测试

```bash
mvn test
```

包含全套单元测试与基于 Testcontainers 的集成测试。核心覆盖：三种协议的 SSE 用量解析与透传、六方向协议转换、工具调用、协议优先级回退、不兼容字段 400、对外模型 ID 重写与直连、首 token 前故障转移、Retry-After 冷却、档位分摊轮询、渠道密钥限流与管理面权限边界。

只运行渠道密钥 RPM、最大并发和故障转移的端到端验证：

```bash
mvn -Dgroups=channel-key-rate-limit test
```

这组测试使用真实 PostgreSQL、Ktor 数据面和可延迟返回的假上游，验证同一密钥的 RPM 额度跨模型共享、限流后切换到备用密钥、并发占满后的切换，以及全部密钥繁忙时返回 `429 + Retry-After` 且请求不会到达上游。

### 前端自动化测试

```bash
npm --prefix web test
```

基于 Node.js 原生测试引擎（`node:test`），毫秒级执行。覆盖多主题注册表（浅色/深色/护眼暖色）、持久化与防闪烁、通用复制工具 `copyText`、`SecretInput`、`CopyButton`、`format` 格式化工具、授权模型展示逻辑、密钥限流配置、密钥轮换/撤销并发保护，以及操作审计和请求日志共用的游标分页与刷新竞态。

关键实现决策记录在 `docs/adr/`，包括 Kotlin/Ktor 选型、v1 协议边界和上游 HTTP 客户端选择。

## 代码审查

PR 会自动触发 PR-Agent 审查，完成后在 PR 里留下中文审查意见。配置、runner 选择和排错见 `docs/pr-agent.md`。

## 路线图

- v1.2：Redis 共享速率与并发状态、多管理员 RBAC。
- v1.3：多副本会话、配置缓存、请求正文按渠道采样。
