# llm-gateway

面向内部团队的 OpenAI 兼容 LLM 网关。v1 接收 `/v1/chat/completions` 与 `/v1/responses`，按渠道授权与对外模型 ID 智能路由，透明重写上游模型名，透传 SSE 流，并记录用量与请求元数据。

## 能力边界

- OpenAI Chat Completions 与 Responses 兼容入口，支持 `stream=true` 与 `stream=false`。
- 渠道按协议配置支持 `chat_completions` / `responses`，路由时按请求协议过滤候选。
- 渠道显式授权上游模型；支持自定义「对外模型 ID」，留空则直接沿用上游模型名对外暴露并路由。
- 自定义对外模型 ID 后，上游私有模型名对调用方完全透明隐藏，仅暴露配置的对外模型名；多个渠道配置相同对外模型 ID 时自动聚合并负载均衡。
- 调用方密钥鉴权，上游凭据加密存储（AES-256-GCM）。
- 流式响应不缓冲、客户端断开向上游传播。
- 管理 API 与 Vue 3 管理界面：
  - 渠道管理：接入点、协议、凭据、对外模型 ID 映射及模型计费单价配置。
  - 多主题支持：浅色（Light）、深色（Dark）、护眼暖色（Sepia）三主题无缝轮转与持久化。
  - 模型列表：查看 `/v1/models` 实际对外暴露结果与 models.dev 临时对比。
  - 调用方密钥与请求日志审计。
- PostgreSQL + Flyway；单实例部署。

明确不做：Anthropic/Gemini 原生协议转换、账单与配额、多租户、语义缓存、MCP、多副本。

## 架构

```
client --Bearer client key--> gateway --Bearer upstream credential--> OpenAI-compatible upstream
                                |
                                +-- PostgreSQL: channels, channel models, client keys, model prices, request logs
```

数据面：`/v1/chat/completions`、`/v1/responses`、`/v1/models`。
管理面：`/admin/api/*`，由会话 Cookie + CSRF Token 保护。
运维面：`/health/live`、`/health/ready`、`/metrics`。

渠道的 `baseUrl` 必须包含版本路径，例如 `https://api.openai.com/v1`。网关按请求协议追加 `/chat/completions` 或 `/responses`，不会自动补 `/v1`。

当 `EXPOSE_UPSTREAM_MODELS=true`（默认开启）时，`/v1/models` 会自动聚合所有启用渠道中已授权的模型：
- **未配置对外模型 ID（留空）**：对外直接使用上游模型名，调用方直接用上游名调用；
- **配置了对外模型 ID**：对外仅暴露配置的对外 ID，上游私有名字被完全隐藏，客户端调用对外 ID 时网关自动重写为实际上游名。

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
| `FIRST_TOKEN_TIMEOUT_MS` | `60000` | 首 token 超时 |
| `IDLE_TIMEOUT_MS` | `60000` | 流空闲超时 |
| `TOTAL_TIMEOUT_MS` | `600000` | 单请求总超时 |
| `MAX_ATTEMPTS` | `2` | 首 token 前最多尝试的候选数 |
| `EXPOSE_UPSTREAM_MODELS` | `false` | 是否把启用渠道的上游模型并入 `/v1/models`，并允许未建别名时直连路由 |
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

模型别名列表：`GET /v1/models`，返回当前启用的别名。请求日志会记录本次请求使用的协议。

管理 API 示例：

```bash
curl -c cookies.txt -X POST http://localhost:8080/admin/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"change-me"}'
```

写操作需要把登录响应里的 `gw_csrf` Cookie 值放到 `X-CSRF-Token` 请求头。

拉取某个渠道的上游模型列表（网关会请求 `{baseUrl}/models`）：

```bash
curl -b cookies.txt http://localhost:8080/admin/api/channels/<channel-id>/models
```

返回 `{"object":"list","data":[{"id":"...","ownedBy":"...","authorized":true}]}`。`authorized` 表示该模型是否已被渠道授权。上游不支持 `/models` 时返回 502，并带上 `upstream_models_error`。

新建渠道时可在保存前发现上游模型：

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/model-discovery \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"baseUrl":"https://api.openai.com/v1","credential":"sk-..."}'
```

编辑渠道时可只传 `channelId`，复用已保存凭据。保存渠道时通过 `models` 数组提交授权模型；省略 `models` 表示保持原授权不变。取消仍被别名候选引用的模型授权会返回 `409 channel_model_in_use`。

渠道写请求还可传 `exposeUpstreamModels`：`true` 允许上游模型直连，`false` 表示仅通过别名暴露；省略时保持原值。

渠道模型映射通过 `modelMappings` 提交：

```json
{
  "modelMappings": [
    {"upstreamModel": "Qwen3.8-Flash", "canonicalModelId": "qwen3.8-flash", "mappingSource": "models_dev"}
  ]
}
```

`models` 字段仍可用于只提交上游模型列表；更新时会保留这些模型已有的规范 ID。`mappingSource` 支持 `manual` 和 `models_dev`，同一渠道内规范 ID 不允许重复。

查看调用方通过 `/v1/models` 获取到的对外结果：

```bash
curl -b cookies.txt http://localhost:8080/admin/api/exposed-models
```

返回 `object`、`exposeUpstreamModels` 和 `data`，每条记录包含 `id`、`source`（`alias` / `upstream`）与 `ownedBy`。管理界面的“模型列表”菜单展示同一结果，并提供原始 JSON 响应预览。

同一页面还会加载 models.dev 目录，展示模型 ID、`canonical_model_id`、模型开发者和服务提供方，并在对外模型表中标记匹配结果。目录接口：`GET /admin/api/models-dev`。上游 `/models` 返回的 `owned_by` 只表示上游元数据，不作为模型开发者；例如 Qwen 通过 OpenAI 兼容接口返回 `owned_by=openai` 时，仍按 canonical ID 的 `alibaba` 命名空间识别开发者。

渠道编辑弹窗的“自动匹配规范 ID”会调用 `POST /admin/api/model-suggestions`，按 models.dev 的精确匹配结果填充规范 ID；未匹配或需要覆盖时可直接手动填写。模型列表页会同时展示对外 ID、来源、上游模型和渠道。

命名建议：对外模型 ID 以 models.dev 的 `canonical_model_id` 为参考，通过模型别名映射到上游原始模型名；上游模型名保持原样。若上游名称与规范别名仅大小写不同，建议在该渠道开启“仅通过别名暴露”，避免两个 ID 同时出现。

探测渠道支持的协议：

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/probe-protocols \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini"}'
```

返回每个协议的 `supported`、`statusCode` 和说明。探测会使用已保存凭据发送最小请求（Chat `max_tokens=1`，Responses `max_output_tokens=1`），可能产生少量 token 费用；不传 `model` 时使用渠道已授权模型中的第一个。

失败请求会在日志中记录 `failureStage`（`request` / `routing` / `upstream` / `streaming` / `client`）、`errorMessage` 和上游返回的 `upstreamErrorCode`。 请求日志同时展示输入、输出、推理和总 token、输出速率（输出 token / 总耗时）与缓存命中率（`cached_tokens / input_tokens`），并记录调用方密钥名称和请求中的推理强度；上游未返回的字段显示“—”，不做估算。渠道列显示请求发生时的渠道名称快照，同时保留 `channelId` 用于精确关联；渠道改名不会改写历史日志。请求日志列表只显示错误码和阶段，完整原因在“详情”弹层中查看；上游响应正文仍然不落库。

### 单请求计费

管理界面的“渠道”列表提供便捷的「配置模型单价」入口，直接为每个渠道已授权的上游模型配置输入、输出与缓存读取单价（每百万 token 单价），在请求日志中实时计算并显示单条请求费用。价格规则接口：`GET/POST /admin/api/model-prices`、`PUT/DELETE /admin/api/model-prices/{id}`。

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
- 上游凭据使用 AES-256-GCM 加密存储，密钥来自 `GATEWAY_MASTER_KEY`；接口只返回“已配置”，不回显。
- 管理面使用 HttpOnly 会话 Cookie、SameSite=Strict 和 CSRF Token。
- 默认不记录 prompt/响应正文；日志中不出现任何凭据。
- 管理界面与指标端点应只在内网暴露。

## 测试

### 后端自动化测试

```bash
mvn test
```

包含全套单元测试与基于 Testcontainers 的集成测试。核心覆盖：Chat Completions 与 Responses 的 SSE 用量解析、协议路由过滤、对外模型 ID 重写与直连、CallExtensions 参数提取与 400 校验、首 token 前重试（含连接失败）、429 映射、首 token 超时取消上游、管理面 CSRF。

### 前端自动化测试

```bash
npm --prefix web test
```

基于 Node.js 原生测试引擎（`node:test`），毫秒级执行。覆盖多主题注册表（浅色/深色/护眼暖色）、持久化与防闪烁、通用复制工具 `copyText`、`SecretInput`、`CopyButton`、`format` 格式化工具及授权模型展示逻辑。

关键实现决策记录在 `docs/adr/`，包括 Kotlin/Ktor 选型、v1 协议边界和上游 HTTP 客户端选择。

## 代码审查

PR 会自动触发 PR-Agent 审查，完成后在 PR 里留下中文审查意见。配置、runner 选择和排错见 `docs/pr-agent.md`。

## 路线图

- v1.1：Anthropic Messages 原生适配、每密钥并发/额度限制、OTel 追踪。
- v1.2：多副本会话、Redis 配置缓存、请求正文按渠道采样。
