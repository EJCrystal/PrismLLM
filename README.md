# PrismLLM

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/EJCrystal/PrismLLM/actions/workflows/ci.yml/badge.svg)](https://github.com/EJCrystal/PrismLLM/actions/workflows/ci.yml)

面向内部团队的单实例自托管 LLM 网关。支持 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages 与 Google Gemini 原生透传，以及**四协议、六组双向（共 12 个方向）的有边界跨协议转换**，提供密钥池容灾、上游分组限流、USD 费用快照与多主题管理界面。

---

## 核心特性

- 🔀 **四协议互通**：支持 OpenAI Chat Completions、Responses、Anthropic Messages 与 Google Gemini，六组双向互转（共 12 个转换方向）；同协议透传，跨协议按 SSE 事件增量转换，存在事件及生命周期状态缓冲，不承诺零缓冲或所有扩展能力无损互转。
- 🛡️ **智能密钥池容灾**：多密钥优先级分档与同档轮询；支持状态码、错误正则、连续/窗口计数的高灵敏度自动停用规则（临时停用单探测自愈与永久停用人工恢复）。
- ⚡ **上游主动分组限流**：密钥级独立 RPM 令牌桶与最大并发限制，超限平滑回退至备用候选，避免上游熔断。
- 🎭 **动态请求伪装与覆盖**：自动模拟 Claude Code、Codex、Google GenAI 客户端请求头及 UA，支持 RFC 7396 请求体补丁，消除第三方客户端调用阻碍。
- 📊 **Vue 3 现代化管理台**：包含渠道编排、密钥池管理、调用方密钥生命周期（轮换/永久撤销）、操作审计与用量大盘；支持浅色、深色与护眼暖色（Sepia）三主题无缝轮转。
- 🧠 **统一模型检测**：渠道列表的「测试」入口提供连通性、智商、指纹与记录四个页签。连通性检测用真实请求验证渠道、凭据与协议；智商检测内置鹈鹕骑行与糖果测试，支持可插拔判分器；指纹检测根据随机数字序列在本地参考库中比对 68 个已知模型。智商与指纹结果按渠道归档在「记录」页签，可按类型、模型与结论筛选；三类检测都不写请求日志、不消耗调用方密钥额度，指纹比对完全在本地完成。
- 💰 **单请求 USD 费用快照**：按用量与“渠道覆盖价 > 模型默认价”核算输入、输出及缓存费用，推理 Token 不重复计费；本次请求单次解析价格并计算费用，供每日额度账本与异步请求日志共用，日志消费者不二次算价。每日额度为按 UTC 准入日期归属、请求结束后结算的软预算，不是预授权硬上限。
- 🔌 **可扩展插件生态**：支持通过 JVM SPI 加载独立的余额与额度查询插件（官方提供 Sub2API / New API 适配器）。

---

## 架构

```
client --[client key]--> PrismLLM --[auth & protocol converter]--> upstream LLMs
                            |
                            +-- PostgreSQL: channels, keys, audit & request logs
```

- **数据面**：`/v1/chat/completions`、`/v1/responses`、`/v1/messages`、`/v1/models`、`/v1beta/models` 及 Gemini 动态端点。
- **管理面**：`/admin/api/*`，由 HttpOnly 会话 Cookie 与 CSRF Token 双重保护。
- **运维面**：`/health/live`、`/health/ready`、`/metrics`（Prometheus 格式）。

---

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
# 复制环境变量模板
cp .env.example .env

# 生成并填写 GATEWAY_MASTER_KEY，设置 ADMIN_PASSWORD
openssl rand -base64 32

# 启动服务
docker compose up -d --build
```

启动后访问管理后台：`http://localhost:8080/admin/`，使用 `.env` 中设置的管理员账号密码登录。

以上为本地启动示例；生产环境必须使用 TLS 并设置 `COOKIE_SECURE=true`，将 `/admin/`、`/admin/api/*` 与 `/metrics` 限制在私网或受控运维入口。默认 Compose 给 gateway 设置 `stop_grace_period: 60s`，为默认 30 秒 Drain 及 server、队列清理留出余量；增大 `DRAIN_TIMEOUT_MS` 时必须同步增大容器或编排器的停止预算，这不是完整停机耗时的绝对上界。部署与安全备份要求见 [配置指南](docs/configuration.md)。

### 方式二：本地运行

本地运行需先确保可访问 PostgreSQL 数据库（可使用 `docker compose up -d postgres` 单独拉起随附数据库，映射至 `127.0.0.1:5432`）。
默认打包测试阶段会通过 Testcontainers 启动临时容器，构建前需确保本地 Docker 守护进程处于运行状态（如需跳过测试可传入 `-DskipTests`）。

```bash
# 1. 按 web/package-lock.json 安装依赖并构建前端静态资源
npm --prefix web ci
npm --prefix web run build

# 2. 构建并运行后端（首次启动会自动通过 Flyway 执行数据库表结构迁移）
mvn package
java -jar prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar
```

---

## 核心环境变量

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `PORT` | `8080` | 服务监听端口 |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/llm_gateway` | PostgreSQL JDBC 连接串 |
| `GATEWAY_MASTER_KEY` | *(必填)* | Base64 编码的 32 字节 AES 密钥（用于敏感凭据加密） |
| `ADMIN_USERNAME` | `admin` | 初始管理员用户名 |
| `ADMIN_PASSWORD` | *(必填)* | 初始管理员密码 |
| `CONNECT_TIMEOUT_MS` | `30000` | 上游连接超时时间（毫秒） |
| `FIRST_TOKEN_TIMEOUT_MS` | `120000`| 流式首数据等待超时（毫秒；沿用变量名，不是语义 Token 的保证） |

完整环境变量清单、代理配置与日志保留策略请参阅 [配置指南](docs/configuration.md)。

---

## 专项文档导航

为了保持主文档清晰简洁，详细设计与操作指南已整理至专项文档：

- 📖 **[API 接口手册](docs/api.md)**：包含四类协议调用示例、模型列表、管理接口、密钥生命周期与审计日志。
- 🔄 **[跨协议转换说明](docs/protocol-conversion.md)**：六组双向协议互转（共十二个有向方向）的映射规则、字段兼容性与边界（详见 [ADR 0013](docs/adr/0013-protocol-conversion.md)）。
- ⚙️ **[配置与部署指南](docs/configuration.md)**：完整环境变量表、容器正向代理（Proxy）网络设置与升级注意事项。
- 🛡️ **[路由与容灾机制](docs/routing-and-resilience.md)**：Canonical ID 聚合、上游分组限流、密钥自动停用四态模型与候选冷却。
- 🎭 **[上游请求覆盖与伪装](docs/upstream-request-overrides.md)**：请求头预设、UA 注入与 JSON Merge Patch 规则。
- 💵 **[计费与用量核算](docs/billing.md)**：费用计算公式、双层单价继承模型与多维度用量报表。
- 🧠 **[模型检测说明](docs/model-detection.md)**：连通性、智商与指纹检测的判定口径、接口与限制，以及指纹参考库的来源和许可。
- 🔌 **[渠道额度插件指南](docs/plugins/channel-quota-provider.md)**：Sub2API 与 New API 额度查询插件安装与自定义开发。
- 📈 **[监控与告警配置](monitoring/README.md)**：Prometheus 采集规则、告警模板与 Grafana 接入。
- 🤖 **[AI 代码审查门禁](docs/ai-code-review.md)**：CodeRabbit → PR-Agent 两级降级规则与所需配置。

---

## 安全边界

- **凭据单向加密**：调用方密钥仅存储 SHA-256 哈希；上游渠道凭据使用 AES-256-GCM 强加密存储，API 与管理界面均不回显明文。
- **管理防护**：管理接口要求 `SameSite=Strict` Cookie 与 `X-CSRF-Token` 校验；数据面 Bearer 密钥严格禁止访问管理端点。管理会话、调用方 RPM/并发、上游密钥 RPM/并发与 OAuth 调度限制以单实例内存为准，不是跨副本共享控制。
- **故障转移边界**：以首数据触发的响应提交为界，而不是第一个有语义的文本 Token；提交前可对可重试上游错误及 Shape Refusal 回退到候选，提交后不切换或重放响应。
- **记录可靠性**：请求日志异步 best effort，队列满、写入失败或强制停机可能丢失记录，不是必达财务账本。额度结算失败通过指标、日志与告警处置，不修改或重放已完成响应，不承诺 exactly-once 或自动补账。
- **恢复准备**：数据库与 `GATEWAY_MASTER_KEY` 必须按版本配套备份，但分开安全存储并验证恢复；只更换环境变量不是主密钥轮换，不能自动重加密已有凭据。
- **隐私保护**：请求日志默认不落库提示词（Prompt）、成功响应正文或完整流式响应；失败请求额外保存经凭据脱敏并截断至 8 KiB 的上游错误正文或终止性错误事件载荷（上游错误正文可能包含上游回显的输入片段）。

---

## 测试

后端测试分类、CI 增量选择、编译产物复用和报告口径见 [测试执行指南](docs/testing.md)。

```bash
# 后端测试套件（含基于 Testcontainers 的全链路测试）
mvn test

# 前端轻量测试套件
npm --prefix web test
```

---

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源协议发布。
