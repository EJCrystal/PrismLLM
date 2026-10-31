# Anthropic Messages 原生透传

在网关数据面新增 Anthropic Messages 兼容入口 `POST /v1/messages`。只有声明支持 `messages` 的渠道可参与路由；上游地址为渠道 `baseUrl` 加 `/messages`。请求与成功响应保持 Anthropic Messages 形状，不在 Messages、Chat Completions 和 Responses 之间转换；请求只重写 `model` 为渠道上的实际模型名，其余 JSON 字段原样保留。

## 鉴权与请求头

- 仅 `/v1/messages` 同时接受调用方 `Authorization: Bearer <gateway-key>` 与 `x-api-key: <gateway-key>`，二者认证同一组网关密钥。网关不会把调用方密钥发给上游。
- 渠道未配置 `protocolAuthTypes.messages` 时，按兼容旧配置使用渠道级 `authType`：`bearer` 使用 `Authorization: Bearer <upstream-credential>`；`anthropic` 使用上游 `x-api-key`。配置 `protocolAuthTypes.messages` 后，Messages 出站请求按该覆盖值选择鉴权方式，其他协议不受影响。
- Messages 请求的 `anthropic-version` 原样转发；缺省时使用 `2023-06-01`。`anthropic-beta` 存在时也会转发。

## 响应、流与用量

- 非流式 JSON 响应体与 Anthropic 上游响应体一致。流式响应逐事件透传 `event:` 与 `data:` 行，不添加 OpenAI `[DONE]` 帧；`message_stop` 为正常结束事件。只有在首个内容块开始或内容增量到达前，网关仍可尝试故障转移。
- 网关自己产生的错误使用 Anthropic `{"type":"error","error":{"type":"...","message":"..."}}` 信封；已识别的 Anthropic 上游错误体和 HTTP 状态原样返回。
- 用量读取普通响应与流中的 `message_start` / `message_delta`。记录的输入 token 为 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`，缓存 token 字段记录 cache-read 数量，输出 token 记录 `output_tokens`，总量由已知输入和输出相加；reasoning 未提供时保持未知。
- 当前价格模型只有一个缓存输入单价，因此 cache-read 按缓存输入价计费，cache-creation 计入未缓存输入价。若要匹配 Anthropic 的 cache-write 定价，后续需扩展独立价格字段。

## 不在本次范围

不把 Anthropic 请求转换成 OpenAI 请求，也不把 OpenAI 请求转换成 Anthropic 请求；不新增 `/v1/messages/count_tokens`；不估算缺失用量。Gemini 原生协议适配继续推迟。

## 理由

Anthropic SDK 与 Claude 工具链可以直接连接网关，同时复用现有别名路由、冷却故障转移、调用方额度和请求日志。保留协议边界可以避免未经授权的字段丢失或语义变化。
