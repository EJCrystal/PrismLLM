# V59 智商检测视觉判分配置迁移说明

V59 新增 `iq_vision_settings` 表，把视觉判分器的开关、模型端点、模型名、API 密钥和视觉超时从部署环境变量搬到管理台「系统配置」，保存后立即生效，不再需要重启网关。

## 结构变更

新增单行表 `iq_vision_settings`：

| 列 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | `integer` | 主键，`check (id = 1)` 保证单行。 |
| `enabled` | `boolean` | 是否启用视觉判分。 |
| `base_url` | `varchar(500)` | OpenAI 兼容端点基础地址；空串表示沿用环境变量。 |
| `model` | `varchar(200)` | 多模态模型名；空串表示沿用环境变量。 |
| `api_key_ciphertext` / `api_key_nonce` / `api_key_key_version` | `text` / `text` / `integer` | AES-256-GCM 密文、随机数与主密钥版本，与渠道凭据同一套 `CredentialCipher`。空串表示没有保存密钥。 |
| `vision_timeout_ms` | `bigint` | 单次视觉评分超时。 |
| `updated_at` | `bigint` | 最后保存时间（毫秒）。 |

纯新增表，不改既有表，无需维护窗口，迁移后表中没有行。

## 行为变化

- **没有行时行为不变**：仍使用 `IQ_VISION_ENABLED` / `IQ_VISION_BASE_URL` / `IQ_VISION_MODEL` / `IQ_VISION_API_KEY` / `IQ_VISION_TIMEOUT_MS`。升级后不做任何页面配置，效果与升级前一致。
- **判分器始终注册**：`vision` 不再依赖 `IQ_VISION_ENABLED=true` 才出现在判分器列表中。关闭状态下选中它会返回 `review`（需人工查看）并说明原因，而不是「判分器不存在」。
- **密钥与端点绑定**：页面端点与环境变量端点（忽略大小写与末尾斜杠）一致时才回退到 `IQ_VISION_API_KEY`。端点改到别的服务后，接口拒绝在既不给新密钥也不显式清除的情况下保存，避免把部署凭据发给新端点。
- **不可热切换的字段**：`IQ_VISION_RENDER_WIDTH`、`IQ_VISION_MAX_IMAGE_BYTES`、`IQ_VISION_BROWSER_TIMEOUT_MS`、`IQ_VISION_BROWSER_PATH` 仍由环境变量决定，改动需重启。管理台会展示浏览器单帧超时与判分总超时，提示「6×单帧渲染 + 视觉调用」是否已超出 `IQ_JUDGE_TIMEOUT_MS`。

## 部署后

- 如需继续用环境变量控制，什么都不用做。
- 如需在页面配置，进入「系统配置 → 智商检测视觉判分」填写并保存。保存时可留空 API 密钥以沿用环境变量（端点一致时），或勾选清除。
- 浏览器仍在启动期解析：镜像里没有 Chrome 时，页面会提示「未找到无头浏览器，视觉判分只会返回需人工查看」。

## 回滚

结构变更可回退（drop table），但 Flyway 会因 checksum 与 schema 版本不一致无法直接启动旧版本。需要回退时按常规流程恢复部署前备份，再启动旧镜像；旧版本不读这张表，保留它也不影响运行。
