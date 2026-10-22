# 请求日志展示 Token 用量、生成速率与推理强度

请求日志增加 `reasoning_tokens`、`reasoning_effort` 和 `client_key_name` 快照，列表直接展示输入、输出、推理、总 token、生成速率与缓存命中率。

## 口径

- 输入 / 输出 / 推理 / 总 token 直接取上游返回的 usage，缺失字段保持 `null`，不估算。
- Chat Completions 的推理 token 取 `usage.completion_tokens_details.reasoning_tokens`；Responses 取 `usage.output_tokens_details.reasoning_tokens`。
- 输出速率 = 输出 token / 总耗时。推理 token 可能发生在首个可见 token 之前，用“首 token 之后”的窗口会让分子分母描述不同时间段，因此统一使用整条请求的窗口。
- 缓存命中率 = `cached_tokens / input_tokens`，仅当两者都存在且输入大于 0 时展示。
- 推理强度取请求中的 `reasoning_effort`（Chat）或 `reasoning.effort`（Responses），保存调用方实际传入的值；未传时显示未设置，不推断上游默认值。
- 调用方密钥名称在写入日志时快照，密钥改名或删除后历史记录仍然可读。

## Consequences

- 上游不返回 reasoning 或 cache 字段时，界面显示 `—`，不会伪装成 0。
- 输出速率包含排队、预填充和传输时间，适合比较端到端吞吐，不等同于纯解码速度。
- 日志表因此多出三个可空列，迁移为 `V5__request_log_usage_details.sql`。
