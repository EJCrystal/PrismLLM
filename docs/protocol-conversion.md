# 协议转换

## 入口与方向

`ProtocolConversion` 保留代理层调用入口，跨协议时由 `ProtocolConverterRegistry` 按 `(inbound, outbound)` 精确选择转换器。同协议不经过注册表：请求只保留原有模型改写逻辑，响应和 SSE 继续透传。

| 转换器 | 调用方请求 | 上游请求 | 返回给调用方 |
| --- | --- | --- | --- |
| MessagesToChatCompletion | Messages | Chat Completions | Messages |
| ChatCompletionToMessages | Chat Completions | Messages | Chat Completions |
| ResponsesToChatCompletion | Responses | Chat Completions | Responses |
| ChatCompletionToResponses | Chat Completions | Responses | Chat Completions |
| MessagesToResponses | Messages | Responses | Messages |
| ResponsesToMessages | Responses | Messages | Responses |

转换器名称表示**请求方向**；同一转换器的 `convertResponse` 和 `streamConverter` 处理相反的返回方向。新增方向需实现 `ProtocolConverter` 并加入注册表，不允许自动组合其它方向形成隐式中转。

## 接口与职责

- `ProtocolConverter` 声明 `inbound`、`outbound`，提供 `convertRequest(request, upstreamModel)`、`convertResponse(response, model)`、`streamConverter(model)`。
- 请求转换使用实际上游模型名；跨协议响应使用调用方的对外模型 ID。转换只操作 JSON，不发网络请求，也不选择渠道。
- 每次调用 `streamConverter` 都创建独立会话，使用 `feed(line)` 输入 SSE 行，最后调用 `finish()` 冲刷尚无空行分隔的末尾事件。结束后再次输入或结束均不重复输出终止事件。
- 流式解析器与编码器按协议分别实现，共享协议中立的 `StreamEvent`、`FinishReason`、`TokenUsage`，不使用 Chat Completions JSON 或 Chat 事件作为中间协议。Messages ↔ Responses 的普通请求和响应也直接映射。
- 转换后的 Chat 工具索引按首次出现顺序连续编号；Responses 输出完整的项与内容生命周期，增量和完成事件复用稳定的项 ID，并提供递增事件序号。流式失败通过 `response.failed.response.error` 返回。
- 跨协议 SSE 单个事件最多累计 1 MiB UTF-8 数据（含多行数据间的换行）；上游行读取也有长度限制，原生透传不受本次限制变更影响。
- 认证、上游 URL、请求覆盖、限流、冷却、重试、响应提交时机、日志和计费仍属于现有代理层。HTTP 状态码与流内错误状态码继续分别记录。

## 支持边界

本轮保持三种协议已有功能范围，不增加管理端选项、数据库字段或新的协议能力：

| 能力 | 行为 |
| --- | --- |
| 文本、系统提示、输出 token 上限、采样参数 | 按目标协议字段映射；Messages 缺失 token 上限时保持原有 4096 默认值 |
| 函数工具、选择工具、工具调用与结果 | 映射声明、调用 ID、参数及结果；直接转换保留文本和工具项目的原始顺序 |
| 并行工具开关 | Messages 的 `disable_parallel_tool_use` 与 Responses/Chat 的 `parallel_tool_calls` 对应 |
| 用量 | 统一计数后输出目标字段；Messages 缓存读写加入输入总量，Responses/Chat 输入量不重复增加缓存 |
| 思考参数 | 沿用已有 budget 与 effort 的分档近似映射，不声称无损 |
| 推理响应 | Messages thinking 可转为 Responses reasoning；直转中无法表示的 redacted_thinking 或缺少 Anthropic 签名的 Responses reasoning 明确报错，不伪造 thinking 块 |
| 流式输出 | 文本、工具参数增量、结束原因、末尾用量与错误；没有结束原因的 EOF 不伪造成功结束，代理层仍检测缺失终止事件的流中断 |
| 安全停止原因 | Chat `content_filter`、Responses `incomplete/content_filter` 与 Messages `refusal` 相互映射，普通响应与流式返回均不降级为自然结束 |
| Gemini | 仅原生透传，不参与跨协议转换 |
| 图片、文件、音频、内置工具 | 仍不支持跨协议转换 |
| Responses 状态续接与存储 | 拒绝 `previous_response_id`、`store=true`、非空 `include`、自动 `truncation`；允许原有无副作用默认值 |
| 结构化输出 | Chat ↔ Responses 保留已有映射；转换到 Messages 仍拒绝 |
| Messages 专有扩展 | `context_management`、`output_config` 等仍明确报错，不通过删除字段绕过 |

未知请求顶层字段及已知不可转换能力抛出带字段路径的 `ProtocolConversionException`。本地请求转换失败不发往上游，不消耗上游尝试次数；响应转换错误和流中断保持现有处理规则。这里不承诺所有供应商扩展字段、思考签名或不同协议特性之间能够无损互转。

## 验证

`ProtocolConversionTest` 保留既有回归；`ProtocolConversionContractTest` 覆盖六方向接口、透传、末帧、错误、异常 EOF 和会话隔离；`MessagesResponsesConversionTest` 覆盖直转顺序、工具参数、缓存用量与不支持字段。数据面集成测试覆盖六方向的普通和流式请求、实际出站端点及尝试日志。
