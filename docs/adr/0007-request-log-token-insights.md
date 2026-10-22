# 请求日志展示 Token 用量、生成速率与推理强度

请求日志增加 `reasoning_tokens`、`reasoning_effort` 和 `client_key_name` 快照，列表直接展示输入、输出、推理、总 token、生成速率与缓存命中率。

## 口径

- 输入 / 输出 / 推理 / 总 token 直接取上游返回的 usage，缺失字段保持 `null`，不估算。
- Chat Completions 的推理 token 取 `usage.completion_tokens_details.reasoning_tokens`；Responses 取 `usage.output_tokens_details.reasoning_tokens`。
- 生成速率 = 输出 token / 生成窗口秒数；生成窗口为 `totalMs - firstTokenMs`，没有首 token 时间（非流式）时退回 `totalMs`。它表示解码阶段速度，不是整条请求的吞吐。
- 缓存命中率 = `cached_tokens / input_tokens`，仅当两者都存在且输入大于 0 时展示。
- 推理强度取请求中的 `reasoning_effort`（Chat）或 `reasoning.effort`（Responses），保存调用方实际传入的值；未传时显示未设置，不推断上游默认值。
- 调用方密钥名称在写入日志时快照，密钥改名或删除后历史记录仍然可读。

## Consequences

- 上游不返回 reasoning 或 cache 字段时，界面显示 `—`，不会伪装成 0。
- 生成速率依赖首 token 时间；非流式请求的速度会把排队和传输时间算进去，横向比较时应以流式请求为主。
- 日志表因此多出三个可空列，迁移为 `V5__request_log_usage_details.sql`。
