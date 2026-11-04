# v1 只做 OpenAI 兼容透传

> 状态（2026-10-29）：部分取代于 [ADR 0010](0010-anthropic-messages-passthrough.md)、[ADR 0013](0013-protocol-conversion.md) 与 [ADR 0014](0014-upstream-request-overrides-and-gemini.md)——新增 Anthropic Messages 和 Gemini 原生入口，并允许 Chat Completions、Responses、Messages 之间转换；后续由 ADR 0013 扩展为 Gemini 参与全部十二个跨协议方向。

v1 只接受 OpenAI Chat Completions 与 Responses 兼容请求，只接入 OpenAI 兼容上游。Anthropic、Gemini 原生协议转换、计费、多租户和语义缓存全部推迟。

## 理由

协议转换是 axonhub 这类项目的主战场，会把 MVP 从数周拖成数月；而内部团队的第一需求是统一入口、别名路由、流式稳定和可排障。请求体只改写 `model`，响应流原样转发，避免强类型 DTO 丢失供应商扩展字段。

## Consequences

调用方必须使用 OpenAI 兼容 SDK。接入 Anthropic 原生模型时，需要先由上游提供 OpenAI 兼容层，或在 v1.1 增加适配器。Chat Completions 与 Responses 之间不做互相转换，渠道只接收其声明支持的协议。用量统一为 `input`/`output`/`reasoning`/`cached`/`total`，拿不到时标记未知，不估算。
