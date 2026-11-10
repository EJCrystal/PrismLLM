# ADR 0013：三协议互通转换

> 状态：已采纳（2026-10-29）

## 决策

- 支持 Chat Completions、Responses、Anthropic Messages 六个方向互转；后续扩展为包含 Gemini 的十二个方向。
- 渠道协议是有序列表；同协议候选始终优先，原生候选不可用或失败后才按渠道顺序选择转换目标。
- 第一版覆盖文本、系统提示、工具调用/结果、流式 SSE、usage 和常用 reasoning effort。
- 图片、文件、音频、Provider 内置工具、Responses `previous_response_id` 等无法无损表达的能力在请求上游前返回 `protocol_conversion_unsupported`。
- 请求日志同时记录入站 `protocol` 与实际出站 `upstream_protocol`；用量报表仍按入站协议统计。

## 后果

- 原生协议路径保持原样透传，转换只发生在协议不匹配的回退路径。
- 转换增加 JSON/SSE 映射开销，但不改变原生路径性能。
- 高级 Provider 能力需要后续逐项扩展，当前选择显式失败而非静默丢字段。

> 2026-11-06 补充：Responses 的 `web_search` 在管理台「系统配置 → Web 搜索」配置搜索后端
> （或设置 `WEB_SEARCH_*`）后由网关代理执行，不再走跨协议转换拒绝；
> 其余 Provider 内置工具仍按本 ADR 显式失败。

> 2026-11-10 补充：Responses 的 `additional_tools` 输入项（Codex "Responses Lite" 形状，
> Codex 对 gpt-6 / gpt-5.6 等 Lite 模型把全部工具放在输入首项、不发 `tools` 字段）在跨协议
> 转换前抬升为请求级 `tools`：`functions` namespace 拆开平铺、同名工具去重、历史调用项去掉
> `namespace` 标记。该映射无损（Chat/Messages/Gemini 的工具本就按请求声明），参照 magpie 的
> liftAdditionalTools（magpie#350、#325）；原生 Responses 透传不受影响。搜索代理同样感知该
> 形状：`additional_tools` 里的 `web_search` 参与检测、域名过滤与工具名占用，剥离逻辑统一
> 在抬升之后执行。
