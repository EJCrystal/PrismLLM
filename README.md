# llm-gateway

面向内部团队的 OpenAI 兼容 LLM 网关。v1 只做一件事：接收 `/v1/chat/completions`，按模型别名选择渠道，透传 SSE 流，并记录用量与请求元数据。

## 能力边界

- OpenAI Chat Completions 兼容入口，支持 `stream=true` 与 `stream=false`。
- 模型别名到有序候选的路由；首 token 前失败最多切换一次。
- 调用方密钥鉴权，上游凭据加密存储。
- 流式响应不缓冲、客户端断开向上游传播。
- 管理 API 与 Vue 3 管理界面：渠道、别名、调用方密钥、请求日志。
- PostgreSQL + Flyway；单实例部署。

明确不做：Anthropic/Gemini 原生协议转换、计费、多租户、语义缓存、MCP、多副本。

## 架构

```
client --Bearer client key--> gateway --Bearer upstream credential--> OpenAI-compatible upstream
                                |
                                +-- PostgreSQL: channels, aliases, client keys, request logs
```

数据面：`/v1/chat/completions`、`/v1/models`。
管理面：`/admin/api/*`，由会话 Cookie + CSRF Token 保护。
运维面：`/health/live`、`/health/ready`、`/metrics`。

渠道的 `baseUrl` 必须包含版本路径，例如 `https://api.openai.com/v1`。网关只在该地址后追加 `/chat/completions`，不会自动补 `/v1`。

## 快速开始

```bash
cp .env.example .env
# 修改 .env 中的 GATEWAY_MASTER_KEY、ADMIN_PASSWORD
docker compose up -d --build
```

管理界面默认在 `http://localhost:8080/admin/`。首次启动会用 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 创建管理员。

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
| `ADMIN_PASSWORD` | 无 | 首次启动创建的管理员密码，必填 |
| `CORS_ORIGINS` | 空 | 允许的浏览器来源，逗号分隔 |
| `REQUEST_BODY_LIMIT_BYTES` | `10485760` | 请求体上限 |
| `CONNECT_TIMEOUT_MS` | `5000` | 上游连接超时 |
| `FIRST_TOKEN_TIMEOUT_MS` | `60000` | 首 token 超时 |
| `IDLE_TIMEOUT_MS` | `60000` | 流空闲超时 |
| `TOTAL_TIMEOUT_MS` | `600000` | 单请求总超时 |
| `MAX_ATTEMPTS` | `2` | 首 token 前最多尝试的候选数 |
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

模型别名列表：`GET /v1/models`，返回当前启用的别名。

管理 API 示例：

```bash
curl -c cookies.txt -X POST http://localhost:8080/admin/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"change-me"}'
```

写操作需要把登录响应里的 `gw_csrf` Cookie 值放到 `X-CSRF-Token` 请求头。

## 安全边界

- 调用方密钥只保存 SHA-256 哈希和前缀，创建时明文只返回一次。
- 上游凭据使用 AES-256-GCM 加密存储，密钥来自 `GATEWAY_MASTER_KEY`；接口只返回“已配置”，不回显。
- 管理面使用 HttpOnly 会话 Cookie、SameSite=Strict 和 CSRF Token。
- 默认不记录 prompt/响应正文；日志中不出现任何凭据。
- 管理界面与指标端点应只在内网暴露。

## 测试

```bash
mvn test
```

集成测试使用 Testcontainers 启动 PostgreSQL。核心覆盖：SSE 用量解析、别名路由、首 token 前重试（含连接失败）、429 映射、首 token 超时取消上游、管理面 CSRF。

关键实现决策记录在 `docs/adr/`，包括 Kotlin/Ktor 选型、v1 协议边界和上游 HTTP 客户端选择。

## 路线图

- v1.1：Anthropic Messages 原生适配、每密钥并发/额度限制、OTel 追踪。
- v1.2：多副本会话、Redis 配置缓存、请求正文按渠道采样。
