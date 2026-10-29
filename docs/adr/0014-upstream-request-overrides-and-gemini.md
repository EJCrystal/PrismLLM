# 上游请求覆盖与 Gemini 原生透传

## 决策

在渠道上新增可选 `requestOverrides`，并以独立单行表保存全局请求覆盖。两层均支持通用和按协议配置的 `headerProfile`、`userAgent`、`headers` 与 JSON Merge Patch `body`。单实例启动时加载全局配置到缓存，保存事务提交后更新缓存；数据面每个请求持有一份缓存快照，在每次候选尝试时从原始请求重新构造上游请求。

按实际出站协议选择默认请求头：Messages → Claude Code，Chat/Responses → Codex，Gemini → Google Gen AI SDK；另提供 Antigravity 与关闭预设选项。最终预设只选择一次，然后顺序应用全局通用、全局协议、渠道通用、渠道协议，避免切换预设后遗留其他客户端头。渠道可关闭全局继承。

`headerProfile` 按渠道协议、渠道通用、全局协议、全局通用从高到低选择第一个非 null 值，缺省为 auto；选择出的预设始终低于显式 UA 和请求头规则。渠道通用高于全局协议，同一层 headers 中的 User-Agent 高于 userAgent。配置指南与完整 API 示例见 [上游请求配置说明](../upstream-request-overrides.md)。

补充 Gemini 原生 `generateContent` / `streamGenerateContent` 入口与模型列表，复用路由、密钥池、分组限流、冷却、首 token 前故障转移、用量与日志。Gemini 模型映射在 URL 中完成，流式上游使用 `alt=sse`；透传所有原生帧，并读取 finishReason 后可能追加的用量帧，不注入 OpenAI `[DONE]`。思考 token 计入输出，同时保留独立 reasoning 统计；缓存 token 已包含在输入中。

## 理由

不同渠道对 UA、客户端标识头或请求参数有不同要求。只有全局 UA 会限制多协议网关；每个请求入口各自实现覆盖则会导致数据面、连通性测试、模型发现和协议探测行为不一致。使用共享策略和显式、可验证的 JSON 补丁，让管理员可以配置差异，同时保持协议与凭据边界。

请求覆盖借鉴 [AxonHub 的出站覆盖阶段](https://github.com/looplj/axonhub/blob/8cdc33a1f51b34c2a54f8463442ad22ecd0f8a32/internal/server/orchestrator/override.go)；客户端头的形状参考 [sub2api 的 Claude 配置](https://github.com/Wei-Shaw/sub2api/blob/a3298b46ebe239fb0720b068a1eb3383955bdac2/backend/internal/pkg/claude/constants.go)、[Codex 身份配对](https://github.com/Wei-Shaw/sub2api/blob/a3298b46ebe239fb0720b068a1eb3383955bdac2/backend/internal/service/openai_codex_identity.go) 和 [Antigravity UA](https://github.com/Wei-Shaw/sub2api/blob/a3298b46ebe239fb0720b068a1eb3383955bdac2/backend/internal/pkg/antigravity/oauth.go)。Google SDK 头参考 [官方 js-genai 客户端](https://github.com/googleapis/js-genai/blob/main/src/_api_client.ts)。实现使用自身的 Kotlin 策略和 RFC 7396，不依赖这些项目的运行时。

## 约束与兼容

- 请求体只能使用 JSON 对象补丁；嵌套对象合并，数组替换，null 删除字段。暂不引入模板执行、字段重命名或脚本。
- model、stream、鉴权、连接/长度/类型/编码及 traceparent 不能通过补丁修改，凭据仍由渠道密钥提供。Content-Encoding 与 Accept-Encoding 均由网关管理，避免明文 JSON/SSE 与压缩编码声明不符。请求头必须合法，整体配置不超过 64KB，请求体改写后继续检查大小。
- 自定义 UA 允许覆盖版本；规范 Codex UA 的 originator/version 自动配对，管理员显式设置时保持该值。UA 删除以空值表达，避免 Java HTTP 客户端补回默认 UA。
- 旧渠道更新省略 requestOverrides 时保持配置；空对象恢复默认继承，Flyway 迁移给已有渠道写入空对象。
- 模型发现可使用未保存的请求头配置；测试与探测使用已保存配置，并保持 128 / 1 token 的输出预算。所有相关管理写操作继续进入脱敏审计。
- Claude、Codex、Google 预设从公开版本源定时刷新，并持久化最近成功快照、版本历史和固定版本；Antigravity 可由可选 HTTPS manifest 覆盖，默认使用内置模板。Google/Antigravity 预设只设置请求头；不实现订阅 OAuth 流程、Antigravity 内部请求封装或协议互转。
