# 上游请求配置：优先级与示例

上游请求配置用于设置 UA、客户端请求头和 JSON 请求体补丁。它作用于实际发往上游的请求，不会将 Chat、Responses、Anthropic 或 Gemini 请求互相转换。

## 配置入口与保存行为

| 范围 | 管理面入口 | API 配置字段 |
| --- | --- | --- |
| 全局默认 | 渠道 → 上游请求配置 | `PUT /admin/api/upstream-request-settings` 的 `overrides` |
| 渠道覆盖 | 新建/编辑渠道 → 上游请求配置 | 渠道创建/更新请求中的 `requestOverrides` |

两处均可选择通用配置或某个协议。协议键为 `chat_completions`、`responses`、`messages`、`gemini`；通用配置直接放在 `overrides` / `requestOverrides` 顶层，协议配置放在 `protocols` 下。

「配置范围」只决定正在编辑哪一层，不会切换或限制渠道路由：

- **通用配置**：应用于全部出站协议，也用于模型发现、连通性测试和协议探测。适合团队标识、通用 UA 等所有协议都能接受的请求头。
- **协议专用配置**：只在实际出站协议匹配时应用。可以分别给 Chat、Responses、Messages 和 Gemini 设置不同 UA、请求头和请求体参数，例如 Responses 的 `reasoning`、Messages 的 `anthropic-beta`、Gemini 的 `generationConfig`。

同一层的协议专用配置高于通用配置。完整的全局/渠道组合顺序仍是：最终预设 → 全局通用 → 全局协议 → 渠道通用 → 渠道协议。

保存后新请求立即使用新配置，重启后保留。全局写入会整体替换 `overrides`；渠道写入时，省略整个 `requestOverrides` 字段会保留原配置，传入该字段则整体替换渠道请求配置。修改其中一个协议时，也要保留还需要的其他协议规则。

全局配置在单实例网关启动时加载到进程内缓存，后续请求复用该快照。通过管理接口保存并完成数据库提交后立即更新缓存；保存失败时继续使用原快照，重启后重新读取持久化配置。正在进行的请求保持自己的快照，下一次请求使用新配置。

全局提交 `{"overrides":{}}` 清除全局自定义规则，恢复按协议自动预设。渠道提交 `"requestOverrides":{}` 清除渠道自定义规则并恢复全局继承；继承的全局规则仍会生效。

## 生效顺序：先选预设，再应用覆盖

### 1. 选择一个最终请求头预设

`headerProfile` 从高到低查找第一个已配置的值：

1. 渠道当前协议
2. 渠道通用
3. 全局当前协议
4. 全局通用
5. 都未配置时使用 `auto`

只选择一次预设，不会先叠加 Claude 预设再叠加 Codex 预设。因此渠道把全局的 Claude 预设改为 Codex 后，不会遗留 Claude 预设中的 `x-stainless-*` 头。管理员显式配置的自定义头仍会按下一步应用。

`auto` 也是一个明确的覆盖值。例如全局 Responses 选择 `claude`，渠道通用选择 `auto`，该渠道的 Responses 请求就恢复使用自动匹配的 Codex 预设。

### 2. 应用 UA、请求头和请求体补丁

请求头从选定的预设开始；请求体从调用方原始 JSON 和网关完成的模型映射开始。覆盖顺序如下，后面的层优先：

| 顺序（低 → 高） | 配置来源 | 应用条件 |
| --- | --- | --- |
| 1 | 选定的协议请求头预设 | 只提供请求头默认值，`none` 不提供预设头 |
| 2 | 全局通用 | 渠道继承全局时 |
| 3 | 全局当前协议 | 渠道继承全局，且存在当前协议规则时 |
| 4 | 渠道通用 | 存在对应配置时 |
| 5 | 渠道当前协议 | 存在当前协议规则时 |

**渠道通用也高于全局当前协议。** 例如全局 UA 是 A、全局 Responses UA 是 B、渠道 UA 是 C、渠道 Responses UA 是 D，则 Responses 最终使用 D；删掉渠道 Responses 的 UA 后使用 C，而不是 B。渠道两处 UA 都未设置时，才使用 B。

**预设低于所有显式 UA 和请求头配置。** 全局通用设置自定义 UA 后，单独在渠道切换 `headerProfile` 并不会清除这个 UA。要使用新预设的 UA，应清除相关自定义 UA、在更高层指定目标 UA，或关闭全局继承。

同一层先设置 `userAgent`，再设置 `headers`。因此同层 `headers` 中的 `User-Agent` 比 `userAgent` 优先；请求头名称不区分大小写。不要在两处重复配置 UA，除非确实需要这个顺序。

`inheritGlobal:false` 只跳过全局通用和全局协议两层，仍会使用渠道配置及默认协议预设。`headerProfile:"none"` 只关闭预设，仍会应用继承的自定义规则；两者作用不同。

## 各协议自动预设

`auto` 按实际出站协议选择，不根据模型名称猜测，也不受渠道使用 Bearer 或 API key 鉴权的影响。

| 出站协议 | `auto` 选择 | 内置默认 UA |
| --- | --- | --- |
| `messages` | `claude`（Claude Code） | `claude-cli/2.1.312 (external, cli)` |
| `chat_completions` | `codex` | `codex_cli_rs/0.165.0 (Linux; x86_64)` |
| `responses` | `codex` | `codex_cli_rs/0.165.0 (Linux; x86_64)` |
| `gemini` | `google`（Google Gen AI SDK） | `google-genai-sdk/2.32.0` |

也可显式选择 `antigravity`，其当前 UA 为 `antigravity/hub/2.23.0 windows/amd64`。例如希望 Gemini 使用它，可在全局或渠道的 Gemini 范围选择 Antigravity，即设置 `protocols.gemini.headerProfile`。

Claude 预设还提供 `x-app` 和 `x-stainless-*`；Codex 提供 `originator` / `version`；Google SDK 提供 `x-goog-api-client`。完整预设头可在管理面展开查看，也可从 `GET /admin/api/upstream-request-settings` 响应的 `profiles` 获取。

网关每小时检查 Claude Code npm 包、Codex GitHub Release 和 Google Gen AI SDK npm 包的最新版本，并将版本写入完整请求头模板。刷新结果持久化到 PostgreSQL；远端失败时继续使用最近成功快照，进程重启后也不会丢失。当前请求只在开始时读取一次快照，因此同一请求的所有故障转移尝试使用同一组请求头。

管理面的「动态预设目录」可以立即刷新、查看当前与历史版本，并固定某个完整请求头快照。固定版本不会随自动刷新变化；恢复「跟随最新版本」后，下一次成功刷新采用最新快照。每个 profile 保留最近 20 个版本。

刷新配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `UPSTREAM_HEADER_REFRESH_ENABLED` | `true` | 是否启动后台自动刷新 |
| `UPSTREAM_HEADER_REFRESH_INTERVAL_MS` | `3600000` | 刷新间隔，范围 60000..604800000 毫秒 |
| `UPSTREAM_HEADER_CATALOG_URL` | 空 | 可选的 HTTPS manifest，用于覆盖完整请求头，包括 Antigravity |

manifest 格式如下。只接受已知的 `claude`、`codex`、`google`、`antigravity` ID；每个 profile 必须包含 `User-Agent`，且不能覆盖鉴权、Host、长度、编码、连接和 Trace 等网关管理头：

```json
{
  "schemaVersion": 1,
  "profiles": [
    {
      "id": "antigravity",
      "name": "Antigravity",
      "version": "2.24.0",
      "headers": {
        "User-Agent": "antigravity/hub/2.24.0 windows/amd64"
      },
      "source": "https://example.com/antigravity/releases"
    }
  ]
}
```

管理 API：`POST /admin/api/upstream-request-settings/header-catalog/refresh` 立即刷新；`PUT /admin/api/upstream-request-settings/header-profiles/{id}/pin` 以 `{"version":"..."}` 固定版本；`DELETE` 同一路径恢复最新版本。

规范 Codex UA（例如 `codex_cli_rs/0.150.0 (Linux; x86_64)`）更新版本时，Codex 预设会自动配对 `originator` / `version`；任何层显式设置或删除这两个头时，以显式配置为准。其他预设的版本头需要按需单独覆盖。

模型发现请求不属于生成协议：优先按渠道鉴权类型识别 Anthropic / Google，否则按渠道唯一声明的协议识别；不能确定协议时只应用通用覆盖，并按鉴权类型选择自动预设。

模型发现 API 中省略 `protocols` 或提交 `protocols:[]` 都表示未指定，回退到已保存渠道的协议；新渠道没有已保存协议时，按上述鉴权规则判断。非空列表仍会校验协议名称，并优先使用这次提交的协议。保存渠道时仍要求至少勾选一个协议。

## 模型发现、协议探测与地址约定

`baseUrl` 是包含上游要求的版本路径的基础地址，不包含 `/models`、`/chat/completions` 等端点。生成与模型发现都在它后面追加端点，不会猜测或补充版本；兼容上游可使用其自身要求的路径。

| 上游 | `baseUrl` 示例 | 模型发现 URL |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `https://api.openai.com/v1/models` |
| Anthropic | `https://api.anthropic.com/v1` | `https://api.anthropic.com/v1/models` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta` | `https://generativelanguage.googleapis.com/v1beta/models` |

Google 官方根地址 `https://generativelanguage.googleapis.com` 和完整模型列表地址 `https://generativelanguage.googleapis.com/v1beta/models` 都不应填入 `baseUrl`；前者缺少版本，后者会重复追加端点。使用其他版本时需确认上游支持该版本。

Gemini 模型发现每页请求 `pageSize=1000`，通过 `nextPageToken` 拉取后续页面，去掉模型 ID 的 `models/` 前缀后跨页去重并排序。每页使用相同的渠道凭据及请求头覆盖。整个发现过程共享 15 秒超时和 2 MB 响应大小上限；后续页失败、累计超限或返回重复分页标记时，整个发现返回错误，不返回不完整列表。GET 模型发现不应用请求体补丁。

例如保存前发现 Google 模型：

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/model-discovery \
  -H 'X-CSRF-Token: <csrf>' -H 'Content-Type: application/json' \
  -d '{"baseUrl":"https://generativelanguage.googleapis.com/v1beta","credential":"<google-api-key>","authType":"google","protocols":["gemini"],"requestOverrides":{"protocols":{"gemini":{"headerProfile":"google"}}}}'
```

管理面「探测协议」是主动发现操作：会请求 Chat、Responses、Messages、Gemini 四种端点，包括尚未勾选的协议，帮助填写渠道支持协议。每个协议最多输出 1 token，可能产生少量费用；结果在编辑界面预选支持协议，保存后才修改渠道配置。数据面请求始终只选择声明了对应协议的渠道。

Gemini 入站兼容 `?key=<gateway-key>`，出站仅使用渠道凭据，不转发调用方查询参数。应用访问日志显式只记录方法、路径、状态和耗时，省略全部查询参数；有反向代理或外部追踪时，也应将访问日志配置为不记录查询参数。支持请求头的调用方可使用 `x-goog-api-key` 或 Bearer 鉴权。

## 字段如何继承、覆盖和删除

| 配置 | 含义 |
| --- | --- |
| `headerProfile` / `userAgent` 省略或为 `null` | 本层不覆盖，继续使用较低层的值 |
| `userAgent:""` | 显式清空 UA；管理面对应「清空 User-Agent」 |
| `headers:{"X-App":"cli"}` | 设置或替换该请求头 |
| `headers:{"X-App":null}` | 删除较低层设置的该请求头；更高层仍可重新设置 |
| `headers:{}` / `body:{}` | 本层没有对应覆盖，保留较低层效果 |
| `body:{"store":false}` | 将请求体的 `store` 改为布尔值 `false` |
| `body:{"store":null}` | 删除请求体的 `store` 字段，而不是发送 JSON `null` |

请求体采用 RFC 7396 JSON Merge Patch：对象逐层合并，数组和标量整体替换，`null` 删除对象成员。各层依次对当前请求体应用补丁，不会先把所有补丁简单拼成一个对象。每次故障转移都会从原始请求重新应用全局规则与本次渠道规则，不携带上一个渠道的改写。

UA 删除有特殊处理：`userAgent:""` 或 `headers:{"User-Agent":null}` 都会发送空 UA 值，避免 Java HTTP 客户端补回默认 UA。只选 `none` 不等于清空 UA。

管理面 UA 输入框留空表示本层不覆盖，不表示删除。通用请求体补丁会作用于全部协议；`reasoning`、`max_tokens`、`generationConfig` 等协议专用字段应放在相应的 `protocols` 规则中，以免其他协议拒绝参数。

## 示例 1：全局保留自动预设，只覆盖 Responses 参数

完整请求见 [upstream-request-global.json](examples/upstream-request-global.json)。该配置保留各协议自动 UA，为全部请求加入团队标识，仅为 Responses 设置推理强度与 `store`。

```json
{
  "overrides": {
    "headerProfile": "auto",
    "headers": {
      "X-Gateway-Team": "platform",
      "X-Remove": "global-default"
    },
    "protocols": {
      "responses": {
        "headers": {
          "X-Client": "global-responses"
        },
        "body": {
          "store": false,
          "reasoning": {
            "effort": "high",
            "summary": "auto"
          }
        }
      }
    }
  }
}
```

以下命令从仓库根目录运行。先登录管理面取得 `cookies.txt`，把 `gw_csrf` Cookie 值替换到 `<csrf>`；登录示例见 [README](../README.md)。

```bash
curl -b cookies.txt -X PUT http://localhost:8080/admin/api/upstream-request-settings \
  -H 'X-CSRF-Token: <csrf>' -H 'Content-Type: application/json' \
  --data-binary @docs/examples/upstream-request-global.json
```

## 示例 2：渠道继承全局，再覆盖 UA、请求头和嵌套参数

[upstream-request-channel.json](examples/upstream-request-channel.json) 是可用于创建渠道的完整请求，包含基础地址、协议、模型映射与密钥。先将上游地址、凭据和模型名替换成实际值；`codex_cli_rs/0.150.0` 是自定义版本示例，不是当前默认预设版本。

其中的请求覆盖为：

```json
{
  "requestOverrides": {
    "inheritGlobal": true,
    "userAgent": "my-channel-client/1.0",
    "headers": {
      "X-Gateway-Team": "channel-a",
      "X-Remove": null
    },
    "protocols": {
      "responses": {
        "userAgent": "codex_cli_rs/0.150.0 (Linux; x86_64)",
        "headers": {
          "x-client": "channel-responses"
        },
        "body": {
          "store": null,
          "reasoning": {
            "effort": "medium",
            "summary": null
          }
        }
      }
    }
  }
}
```

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels \
  -H 'X-CSRF-Token: <csrf>' -H 'Content-Type: application/json' \
  --data-binary @docs/examples/upstream-request-channel.json
```

结合示例 1，发往该渠道的 Responses 请求最终包含：

| 字段 | 最终值 | 原因 |
| --- | --- | --- |
| `User-Agent` | `codex_cli_rs/0.150.0 (Linux; x86_64)` | 渠道 Responses 高于渠道通用及预设 |
| `originator` / `version` | `codex_cli_rs` / `0.150.0` | 与规范 Codex UA 自动配对 |
| `X-Gateway-Team` | `channel-a` | 渠道通用覆盖全局通用 |
| `X-Client` | `channel-responses` | 渠道协议覆盖全局协议，名称大小写不影响匹配 |
| `X-Remove` | 不发送 | 渠道通用显式删除 |
| `reasoning.effort` | `medium` | 渠道协议覆盖全局协议 |
| `reasoning.summary`、`store` | 请求体中不包含 | 渠道协议补丁删除这些字段 |

`model` 仍按渠道映射从 `custom-reasoner` 改为 `upstream-model`；其他未命中的请求体字段保留。

## 示例 3：渠道关闭全局继承与预设，并清空 UA

[upstream-request-isolated-channel.json](examples/upstream-request-isolated-channel.json) 提供完整创建请求，其覆盖配置为：

```json
{
  "requestOverrides": {
    "inheritGlobal": false,
    "headerProfile": "none",
    "userAgent": "",
    "headers": {
      "X-Client": "isolated"
    },
    "protocols": {
      "responses": {
        "body": {
          "store": false
        }
      }
    }
  }
}
```

该渠道不会收到示例 1 的全局团队头、全局 Responses 推理参数或客户端预设头，只应用自己的请求覆盖；UA 发送空值。网关仍使用渠道密钥鉴权，并管理必要的传输头与 `traceparent`。创建命令与示例 2 相同，将文件路径换成 `docs/examples/upstream-request-isolated-channel.json`。

## 校验与管理测试

- 请求头与 UA 必须合法；每层最多 64 个请求头，每个值最多 8192 字符，整体请求覆盖配置最多 64KB，JSON 嵌套最多 32 层。
- 补丁不能修改顶层 `model` / `stream`，也不能覆盖鉴权、Cookie、Host、连接/长度/类型/编码头及 `traceparent`。`Content-Encoding` 和 `Accept-Encoding` 均禁止设置或删除：网关发送明文 JSON，并按明文解析上游 JSON/SSE，不支持通过覆盖请求头要求压缩。凭据通过渠道密钥配置，模型名通过模型映射配置。
- 生成请求、连通性测试和协议探测均应用请求体补丁；模型列表使用请求头规则，不使用请求体补丁。改写后的生成请求仍受请求体大小上限约束。
- 模型发现支持尚未保存的渠道请求头规则；连通性测试和协议探测使用已保存规则，编辑后先保存再测试。输出预算仍分别限制为 128 / 1 token，包括 Chat 的 `max_completion_tokens`。
- Google / Antigravity 预设只设置请求头，不实现订阅 OAuth 登录、自动续期或 Antigravity 内部请求封装。上游必须支持当前请求协议与配置的凭据类型。
