# PrismLLM 架构边界

PrismLLM 是 Maven 模块化单体：`prism-app` 负责组装运行时并承载 HTTP 入口，PostgreSQL 保存持久化状态；管理台 `web/` 独立构建，只通过管理 API 与后端交互。

## 模块职责

| 模块 | 主要职责 |
| --- | --- |
| `prism-db-migrations` | Flyway 数据库迁移。 |
| `prism-test-support` | 跨模块测试支持。 |
| `prism-shared` | 跨模块共用的领域类型、错误与基础工具。 |
| `prism-protocol` | 协议数据结构、流事件编解码与显式协议转换。 |
| `prism-channel` | 渠道及上游凭据相关模型与持久化。 |
| `prism-routing` | 上游模型目录、路由配置读取与候选解析。 |
| `prism-resilience` | 候选可用性、冷却及密钥自动禁用策略。 |
| `prism-subscription-oauth` | 订阅账号 OAuth 接入与令牌生命周期。 |
| `prism-quota` | 调用方额度、请求限制与用量结算。 |
| `prism-pricing` | 模型价格解析与费用计算。 |
| `prism-request-log` | 请求日志写入、查询及请求驱动的健康数据。 |
| `prism-admin` | 管理员数据与管理操作审计持久化。 |
| `prism-app` | Ktor HTTP 路由、运行时装配、应用生命周期与跨模块集成测试。 |

## 协议适配与决策记录

- 跨协议转换服务于第三方客户端接入：Claude Code、Codex、Google GenAI SDK 等使用各自原生协议，而渠道可能提供不同协议。原生协议路径保持透传；跨协议按明确的入站与出站协议方向转换，不隐式串接转换器；无法表达的能力明确拒绝。方向矩阵与字段边界见[协议转换说明](protocol-conversion.md)及 [ADR 0013](adr/0013-protocol-conversion.md)。
- ADR 0002、0004、0010 中旧的协议范围声明，以及 ADR 0008、0010 中旧的计费范围声明，已明确作为历史记录保留原文。判断当前能力以 `CONTEXT.md` 和现行专项文档为准；不要只为消除实现演进造成的文字漂移而改写这些 ADR。

## 组合根与边界

- `prism-app` 是组合根，负责把路由、仓储、策略与服务装配为可运行服务。数据面和管理面的 HTTP 路由、跨模块请求编排及集成测试有意留在这里；不按旧包目录图把这些职责拆散到领域模块。
- 独立模块提供可复用的领域、协议、持久化或策略能力，不因此自动拥有对应的 HTTP 路由。管理台也不直接访问数据库或共享 Kotlin 类型，只依赖管理 API 契约。
- 请求来源元数据由 `prism-app` 从 Ktor 请求中解析，再交给请求日志流程；日志写入与持久化由 `prism-request-log` 承载。请求日志来源 IP 仅在直连对端属于 `TRUSTED_PROXY_CIDRS` 时采信转发头，解析顺序与链处理见 [已接受的边界](../CONTEXT.md#已接受的边界)。访问日志 User-Agent 上限为 512 字符；请求日志复用 IP 解析，但 User-Agent 单独清理并限制为 300 字符。管理操作审计独立记录直连对端地址，不复用请求日志的转发头解析规则。

以上模块清单以根 `pom.xml` 为准。旧文档中的 `api`、`repo`、`report` 等目录映射属于拆分前结构，不作为当前模块边界。
