# ADR 0013：三协议互通转换

> 状态：已采纳（2026-10-29）

## 决策

- 支持 Chat Completions、Responses、Anthropic Messages 六个方向互转。
- 渠道协议是有序列表；同协议候选始终优先，原生候选不可用或失败后才按渠道顺序选择转换目标。
- 第一版覆盖文本、系统提示、工具调用/结果、流式 SSE、usage 和常用 reasoning effort。
- 图片、文件、音频、Provider 内置工具、Responses `previous_response_id` 等无法无损表达的能力在请求上游前返回 `protocol_conversion_unsupported`。
- 请求日志同时记录入站 `protocol` 与实际出站 `upstream_protocol`；用量报表仍按入站协议统计。

## 后果

- 原生协议路径保持原样透传，转换只发生在协议不匹配的回退路径。
- 转换增加 JSON/SSE 映射开销，但不改变原生路径性能。
- 高级 Provider 能力需要后续逐项扩展，当前选择显式失败而非静默丢字段。
