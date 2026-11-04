# PrismLLM 配置指南

本文档包含 PrismLLM 的全部环境变量配置项、容器代理网络设置与数据库迁移注意事项。

---

## 环境变量配置项

通过 `.env` 或容器环境变量提供以下配置。生成主密钥：

```bash
openssl rand -base64 32
```

### 基础与安全配置

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `PORT` | `8080` | HTTP 服务监听端口。注意：默认 `docker-compose.yml` 端口映射固定为 `8080:8080`，若修改此变量需同步调整 Compose 的 `ports` 映射 |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/llm_gateway` | PostgreSQL JDBC 连接 URL。注意：默认 Compose 模式下已固定连接容器内 `postgres`，修改需同步调整 `docker-compose.yml` |
| `DATABASE_USER` | `llm_gateway` | 数据库用户名（Compose 模式下需与 `postgres` 容器配置同步修改） |
| `DATABASE_PASSWORD` | `llm_gateway` | 数据库密码（Compose 模式下需与 `postgres` 容器配置同步修改） |
| `GATEWAY_MASTER_KEY` | *(无)* | Base64 编码的 32 字节 AES 密钥（必填，用于加密存储渠道上游凭据） |
| `ADMIN_USERNAME` | `admin` | 首次启动创建的管理员用户名 |
| `ADMIN_PASSWORD` | *(无)* | 首次启动创建的管理员密码（必填；后续修改以数据库为准） |
| `COOKIE_SECURE` | `false` | 管理面 Session Cookie 是否只走 HTTPS；生产环境应设为 `true` |
| `CORS_ORIGINS` | 空 | 允许的跨域浏览器来源（逗号分隔） |
| `TRUSTED_PROXY_CIDRS` | 空 | 可信反向代理 IP/CIDR（逗号分隔）；仅来自这些网段的连接会采用转发头。`X-Forwarded-For` 和 `Forwarded` 从右向左跳过可信代理地址 |
| `STATIC_DIR` | `web/dist` | 管理界面静态资源目录 |

### 请求控制与网络超时

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `REQUEST_BODY_LIMIT_BYTES` | `10485760` | 请求体体积上限（默认 10 MB） |
| `CONNECT_TIMEOUT_MS` | `30000` | 连接上游超时时间（毫秒，默认 30 秒） |
| `FIRST_TOKEN_TIMEOUT_MS` | `120000` | 流式请求首 Token 超时时间（默认 120 秒） |
| `IDLE_TIMEOUT_MS` | `60000` | 流式传输空闲帧超时时间（默认 60 秒） |
| `TOTAL_TIMEOUT_MS` | `600000` | 单请求总耗时上限（默认 10 分钟） |
| `MAX_ATTEMPTS` | `2` | 首 Token 返回前最多尝试的候选上游数量 |
| `DRAIN_TIMEOUT_MS` | `30000` | 优雅停机 Drain 等待时间（默认 30 秒） |

### 故障冷却与自动恢复

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `COOLDOWN_TRANSIENT_BASE_MS` | `30000` | 瞬时错误（连接失败、超时、空流、5xx）冷却起步时长 |
| `COOLDOWN_TRANSIENT_MAX_MS` | `600000` | 瞬时错误冷却上限（连续失败翻倍递增，最高 10 分钟） |
| `COOLDOWN_RATE_LIMIT_DEFAULT_MS`| `30000` | 上游 429 无 `Retry-After` 响应头时的默认冷却时长 |
| `COOLDOWN_RATE_LIMIT_MAX_MS` | `60000` | 上游 429 冷却上限（遵循 `Retry-After` 但封顶 60 秒） |
| `COOLDOWN_AUTH_FAILURE_MS` | `600000` | 401/403 鉴权失败时的密钥级冷却时长 |
| `KEY_AUTO_RECOVERY_INTERVAL_MS`| `30000` | 自动临时停用密钥的到期扫描与恢复清理间隔 |
| `TTFT_SLOW_THRESHOLD_MS` | `10000` | 流式请求首 Token 耗时软阈值（毫秒） |
| `TTFT_SLOW_STREAK` | `3` | 首 Token 连续超过软阈值的次数，达到后触发候选临时冷却 |

### 上游请求预设与插件

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `UPSTREAM_HEADER_REFRESH_ENABLED`| `true` | 是否定时自动刷新 Claude、Codex 与 Google GenAI 请求头预设 |
| `UPSTREAM_HEADER_REFRESH_INTERVAL_MS` | `3600000` | 请求头预设刷新轮询周期（默认 1 小时） |
| `UPSTREAM_HEADER_CATALOG_URL` | 空 | 可选的 HTTPS Manifest 链接，用于自定义覆盖全局请求头模板 |
| `QUOTA_PLUGIN_DIR` | `plugins` | 渠道额度插件 JAR 所在目录；目录不存在时不加载扩展 |

---

## 容器出站代理配置（可选）

镜像与默认 `docker-compose.yml` 默认直连上游，不开启代理。若网关部署在需要经过正向代理访问上游外网的服务器上，可在 `.env` 中按以下规则配置：

网关使用 Ktor Java 异步 HTTP 引擎。仅设置 Linux 环境变量 `HTTP_PROXY` 不足以影响 JVM 底层出站连接；**必须配合 `JAVA_TOOL_OPTIONS`** 注入 Java 网络属性：

```dotenv
HTTP_PROXY=http://proxy.example:8080
HTTPS_PROXY=http://proxy.example:8080
NO_PROXY=localhost,127.0.0.1,::1,postgres
JAVA_TOOL_OPTIONS=-Dhttp.proxyHost=proxy.example -Dhttp.proxyPort=8080 -Dhttps.proxyHost=proxy.example -Dhttps.proxyPort=8080 -Dhttp.nonProxyHosts=localhost|127.*|[::1]|postgres
```

- `http.nonProxyHosts` 使用 `|` 分隔；必须包含数据库（如 `postgres`）以及私网内网主机。
- 宿主机代理连接：若代理在宿主机上运行，可利用 Compose 提供的 `host.docker.internal:host-gateway` 映射连接。
- 使配置生效：`docker compose up -d --no-build --no-deps gateway`。

---

## 请求日志保留与自动清理

请求日志清理遵循批处理设计，避免大事务锁表影响数据面转发：

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `REQUEST_LOG_RETENTION_DAYS` | `30` | 请求日志保留天数（范围：1 ~ 3650 天） |
| `REQUEST_LOG_CLEANUP_ENABLED` | `true` | 是否启用后台定时自动清理（仅作为初始默认值；首次通过管理 API 修改后，以数据库持久化设置为准） |
| `REQUEST_LOG_CLEANUP_INTERVAL_MS`| `3600000` | 自动清理触发间隔（默认 1 小时） |
| `REQUEST_LOG_CLEANUP_BATCH_SIZE` | `5000` | 单批次清理删除的最大记录数 |

- 管理端可通过 `GET /admin/api/request-log-retention` 查看，`PUT /admin/api/request-log-retention` 动态开关。
- 每日额度账本（`client_key_quota_usage`）不受日志保留期影响，独立安全存储。

---

## 数据库升级与迁移注意事项

PrismLLM 在启动时自动通过 Flyway 执行表结构迁移。

- 从 V44 或更早版本升级时，V45 会为 `request_logs` 添加列并创建索引。
- Flyway 迁移在网关启动时自动执行。PostgreSQL 在迁移期间会持有排他表锁，建议在业务维护窗口停止旧实例，启动新版本网关并等待启动时执行的迁移自动完成，确认 `/health/ready` 健康检查就绪后再恢复流量。
- 详细迁移背景请参考 [V45 渠道健康度迁移注意事项](migrations/V45-channel-health.md)。
