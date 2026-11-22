# PrismLLM API 手册

PrismLLM 分为面向客户端调用的**数据面接口**和由管理员专用的**管理面 API**。

---

## 一、数据面接口（Data Plane）

所有数据面调用需通过调用方密钥（Client Key，前缀 `sk-gw-...`）鉴权。

### 1. OpenAI Chat Completions

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{
    "model": "fast-chat",
    "stream": true,
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### 2. OpenAI Responses

```bash
curl http://localhost:8080/v1/responses \
  -H "Authorization: Bearer sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{
    "model": "fast-chat",
    "stream": true,
    "input": "你好"
  }'
```

### 3. Anthropic Messages

支持 Bearer Token 或 `x-api-key` 鉴权头：

```bash
curl http://localhost:8080/v1/messages \
  -H "x-api-key: sk-gw-..." \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-fast",
    "max_tokens": 128,
    "messages": [{"role": "user", "content": "你好"}]
  }'
```

### 4. Google Gemini 格式调用

Gemini 原生路由端点支持 Bearer 鉴权、`x-goog-api-key` 或 `?key=`：

```bash
curl "http://localhost:8080/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse" \
  -H "x-goog-api-key: sk-gw-..." \
  -H "Content-Type: application/json" \
  -d '{
    "contents": [{"role": "user", "parts": [{"text": "你好"}]}]
  }'
```

### 5. 可用模型发现

- `GET /v1/models`：以 OpenAI 标准格式返回符合路由、授权及目录规则的对外模型 ID，附带 `owned_by: "PrismLLM"`。
- `GET /v1beta/models`：以 Gemini 格式返回模型列表。

模型只有在**已保存路由中至少一条可路由凭据**（静态密钥或订阅账号）支持其活跃映射，且符合调用方访问范围及列表端点的原生协议规则时才会被列出。只有渠道映射、不配置路由或保存空路由都不会使模型进入列表；未选中的其他可用凭据也不会补入。模型列出后，请求仍需通过额度、账号窗口/并发等准入检查。

请求一个未列出的模型时，按原因返回不同状态：

| 场景 | 状态码 | 错误码 |
| :--- | :--- | :--- |
| 模型在当前配置范围内不存在，或请求使用未对外暴露的上游 ID | `404` | `model_not_found` |
| 模型已配置且协议可达，但尚未保存路由、路由为空，或已选凭据均不可用 | `503` | `model_credentials_unavailable` |
| 模型已配置，但当前协议不受支持且无法转换 | `400` | `protocol_not_supported` |

`model_credentials_unavailable` 不表示可以使用未选凭据兜底。缺少路由或路由为空时需由管理员显式选择凭据；凭据停用时需等待自动恢复或人工处理。只有路由中存在符合本次授权及协议范围的临时停用静态密钥时才会返回 `Retry-After`，取这些密钥中最早的可路由时间；密钥被探测租约占用时，重试提示同样等到租约结束。人工与永久停用不会自行恢复，因此不给出重试提示。

---

## 二、管理面接口（Admin API）

管理面接口均挂载在 `/admin/api/*`，由会话 Cookie 与 CSRF 令牌保护。

### 通知管理

| 方法与路径 | 行为 |
| --- | --- |
| `GET /admin/api/notification-settings` | 返回 `enabled`、`consecutiveFailureThreshold`、`updatedAt` |
| `PUT /admin/api/notification-settings` | 完整保存全局开关和连续失败阈值（1..100） |
| `GET /admin/api/notification-targets` | 列出目标、凭据状态（是否配置/是否可解密）和最近投递结果 |
| `POST /admin/api/notification-targets` | 新建目标，返回 201 |
| `PUT /admin/api/notification-targets/{id}` | 替换目标配置并可写入或清除加密凭据；必须携带此前 GET 返回的 `revision`，版本冲突返回 409 |
| `DELETE /admin/api/notification-targets/{id}` | 删除目标，取消待投递任务，保留历史，返回 204 |
| `POST /admin/api/notification-targets/{id}/test` | 在全局及目标启用时排队测试通知，返回 202 `{deliveryId,eventId}` |
| `GET /admin/api/notification-deliveries?targetId=&page=1&pageSize=20` | 可选目标筛选，返回 `{items,total,page,pageSize}`；pageSize 上限 100 |

目标输入包含 `name`（最多 100 字符）、`type`（webhook/feishu）、`enabled`、`events`（非空事件类型列表）、`channelIds`（最多 100 个；空列表为全部）、可选的 `templateTitle`（≤100 字符、不能换行）与 `templateBody`（≤4096 字符、支持换行和 Markdown，仅飞书目标使用，留空为默认卡片；未知变量返回 400 `invalid_notification_template`），以及只写字段 `credentials`：`url`、`bearerToken`、`signingSecret` 及对应 `clearUrl`、`clearBearerToken`、`clearSigningSecret`。凭据在服务端加密保存，接口不回显明文或密文；字段留空表示保持原值，`clear` 标记表示删除，同一字段同时填写并清除返回 400 `invalid_notification_credentials`，非法 URL 返回 400 `invalid_notification_url`。创建无需提交 `revision`；更新必须提交编辑时读取的正整数 `revision`，缺省或非正数返回 400，版本已变化返回 409 `notification_target_changed`，不覆盖当前配置。发生冲突时，编辑器保留草稿并显示错误，但不会更新 `revision`。编辑器打开时，“刷新状态”和“添加目标”不可用，取消编辑会丢弃草稿。请先复制草稿内容并取消编辑，刷新通知目标后重新打开编辑器，核对配置并再保存。输出增加 `id`、`templateTitle`、`templateBody`、`revision`、`updatedAt`、`credentials`（`urlConfigured`、`bearerTokenConfigured`、`signingSecretConfigured` 及 `urlUnreadable`、`bearerTokenUnreadable`、`signingSecretUnreadable`）和 `latestDelivery`。投递状态为 pending、in_flight、succeeded、failed、cancelled。

所有接口要求管理员会话，写接口要求 CSRF。测试通知会实际发送消息；保存配置本身不发送测试消息，测试按钮不可盲目重试。完整事件与签名契约见 [通知指南](notifications.md)。

### 1. 登录与 CSRF 机制

```bash
curl -c cookies.txt -X POST http://localhost:8080/admin/api/login \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "your-password"}'
```

- 登录成功后返回 `HttpOnly` 的会话 Cookie 以及一个 `gw_csrf` Cookie。
- **所有后续写操作（POST / PUT / DELETE）** 必须在请求头中附带：
  `X-CSRF-Token: <gw_csrf 的值>`。

### 2. 渠道上游与密钥池配置

渠道提交采用多密钥池（Key Pool）模型：

```json
{
  "name": "openai-primary",
  "baseUrl": "https://api.openai.com/v1",
  "authType": "bearer",
  "protocolAuthTypes": { "responses": "anthropic" },
  "protocols": ["chat_completions", "responses"],
  "enabled": true,
  "modelMappings": [
    { "upstreamModel": "gpt-4o-mini", "canonicalModelId": "fast-chat" }
  ],
  "keys": [
    {
      "name": "primary-key",
      "credential": "sk-upstream-1",
      "state": "ACTIVE",
      "priority": 1,
      "models": ["gpt-4o-mini"],
      "rateLimit": {
        "enabled": true,
        "requestsPerMinute": 60,
        "maxConcurrentRequests": 3
      }
    },
    {
      "name": "backup-key",
      "credential": "sk-upstream-2",
      "state": "ACTIVE",
      "priority": 2,
      "models": ["gpt-4o-mini"]
    }
  ]
}
```

- `state`：密钥状态。
  - **客户端可提交状态**：`ACTIVE`（正常启用）、`MANUAL_DISABLED`（人工停用）。
  - **接口可返回的状态**：包含上述人工状态，以及由网关容灾规则自动触发的 `AUTO_TEMPORARY`（自动临时停用，到期后通过单探测抢占租约自愈）与 `AUTO_PERMANENT`（自动永久停用，需管理员人工重新启用）。客户端提交 `AUTO_*` 自动状态会被拒绝。
- 凭据加密：网关入库前使用 `GATEWAY_MASTER_KEY` 进行 AES-256-GCM 加密，前端界面和读取接口永不回显明文。

#### 渠道出口代理

在渠道编辑页的「密钥」中维护出口代理，并在每个静态密钥的「出口代理」下拉框中选择绑定。一个渠道最多配置 100 个代理；多个密钥可以共用同一代理，但一个密钥最多绑定一个，且不能引用其他渠道的代理。订阅 OAuth 账号仍使用其独立的账号代理，不继承静态密钥配置。

渠道创建、更新和查询增加 `egressProxies`，每项为 `{ "id": "proxy-us", "name": "美国出口", "url": "http://127.0.0.1:7897" }`。ID 在渠道内唯一且长度为 1..36，名称唯一且长度为 1..100；地址最多 500 个字符。密钥写入和查询增加可空标量 `egressProxyId`。

- 创建时不配置代理或密钥不绑定代理，沿用网关现有出口；不会在渠道代理列表中自动选择或轮换。
- 更新时省略 `egressProxies` 保持原代理列表；提交时完整替换。省略 `egressProxyId` 或传 `null` 保持原绑定；通过 `clearEgressProxy: true` 显式解除绑定，不能同时提交非空 `egressProxyId`。
- 删除代理前必须解除或修改所有绑定，也可在同一次更新中解除绑定并删除代理。无效绑定或代理配置返回 `400 invalid_channel_proxy`，整个更新回滚。
- 支持无认证或账号密码认证的 HTTP 正向代理（HTTP Basic，也可通过 CONNECT 访问 HTTPS 上游）和 SOCKS5 代理（RFC 1929 账号密码认证），例如 `http://127.0.0.1:7897`、`socks5://127.0.0.1:1080`。未指定端口时分别使用 80、1080。SOCKS5 的目标域名交给代理解析，不依赖网关本地 DNS；HTTP、SOCKS5 可在同一渠道并存，连接池按协议、代理地址及认证身份隔离，修改密码后不会复用旧凭据的连接池。不接受 SOCKS4、`socks5h://`、HTTPS 代理地址、路径、查询参数或片段。非回环代理主机需通过 `CHANNEL_PROXY_ALLOWED_HOSTS` 显式放行；例如 Docker 内访问宿主机代理须配置 `host.docker.internal`。该白名单独立于 OAuth 账号代理白名单。
- 认证通过代理对象的 `username`、`password` 单独填写，不能嵌入 URL。账号和新密码各为 1..255 个 UTF-8 字节，不允许控制字符；HTTP 代理账号不能包含冒号。密码保留原始首尾空格，不自动裁剪。新启用认证必须同时提供账号、密码；更新时省略账号保留原账号，省略密码或传空字符串保留原密码。`clearAuthentication: true` 清除账号和密码，不能同时提交非空账号或密码。界面中关闭「账号密码认证」并保存即执行清除。
- 查询代理只增加 `username`、`passwordSet`，不返回密码或密文。密码通过网关主密钥加密，并与代理 ID 绑定保存；管理面查询响应、审计元数据和连接池标识不包含明文密码。草稿模型发现也支持相同的账号密码字段；编辑已保存代理时可留空密码复用该渠道内同 ID 代理的已保存密码。HTTP Basic 和 SOCKS5 账号密码协商本身不提供链路加密，请使用可信代理或安全隧道。
- 流式及非流式转发、模型目录读取、模型发现、连通性测试和协议探测都使用所选密钥的出口。代理配置无效或连接失败不会将同一密钥改为直连；原有模型路由仍可按保存顺序尝试其他凭据。
- 保存前的 `POST /admin/api/channels/model-discovery` 可提交 `egressProxy` 对象测试草稿代理；省略时使用已选保存密钥的绑定，`clearEgressProxy: true` 显式使用网关原出口。草稿测试不保存代理或绑定。

数据库迁移 V79 为已有渠道和密钥初始化空代理列表与空绑定，不改变既有出口行为。

### 2.1. 模型路由（公共模型凭据顺序）

模型路由以对外模型 ID（Canonical / public ID）为键，保存显式、有序的凭据身份列表。它与渠道模型映射及调用方访问范围分别配置：映射决定真实上游模型名，访问范围及凭据资格决定可否调用，路由列表决定尝试顺序。

| 方法与路径 | 参数与响应 |
| --- | --- |
| `GET /admin/api/model-routes?q=&offset=&limit=` | `q` 为去除首尾空白后、不区分大小写的模型 ID 子串，最长 200 字符；`offset` 默认 `0`，`limit` 默认 `100`、范围 `1..500`。返回 `{items, total, offset, limit}`；每项含 `modelId`、`configured`、`revision`、`entryCount`、`availableCount`。列表包含当前模型目录与历史保存路由的并集。 |
| `GET /admin/api/model-routes/options` | 调用方访问范围编辑器使用的紧凑路由目录，返回 `{models:[{modelId, revision, entries:[{channelId, credentialType, credentialId, selectable, reason?}]}]}`；不包含凭据名称、密文或明文。 |
| `GET /admin/api/model-routes/detail?modelId=` | 精确读取公共模型 ID。返回 `modelId`、`configured`、`revision`、已保存顺序的 `entries` 和当前目录的 `availableCredentials`。 |
| `PUT /admin/api/model-routes` | 完整替换一个模型的列表，要求管理员会话与 CSRF；请求必须包含 `modelId`、`expectedRevision` 和 `entries`。成功返回最新详情，revision 递增。 |

模型 ID 位于查询参数或 JSON 字段中，不作为路径段；含 `/` 等字符时用查询参数编码。详情中的每个凭据包含三个身份字段，以及只读的 `channelName`、`credentialName`、`upstreamModels`、`protocols`、`selectable` 与不可用原因 `reason`；没有凭据或 token 明文。`availableCount` 是目录中当前可选择的身份数，不是已选条目数；`availableCredentials` 的目录排序不改变已保存 `entries`。调用方访问范围的新增模型白名单/黑名单 ID 必须已有模型路由；已有选择在路由移除后保留并标记失效，不阻塞其他字段保存。

```json
{
  "modelId": "team/chat",
  "expectedRevision": 3,
  "entries": [
    { "channelId": "channel-b", "credentialType": "oauth", "credentialId": "account-1" },
    { "channelId": "channel-a", "credentialType": "static", "credentialId": "key-1" }
  ]
}
```

- `entries` 只提交身份：`channelId`、`credentialType`（严格为 `static` 或 `oauth`）和 `credentialId`。同一三元组不能重复；静态/OAuth 的同名 ID 是不同身份。不要回传名称、上游别名、协议或可用性元数据。
- `modelId` 长度 `1..200`，不得有首尾空白或控制字符；`expectedRevision` 必须是有效非负版本。首次配置使用详情返回的 revision `0`。并发保存版本冲突返回 HTTP 409 `model_route_revision_changed`；应保留草稿、刷新状态并由管理员核对，不能自动覆盖。
- 新选入的身份必须仍在该模型当前目录中且 `selectable=true`，否则返回 `400 invalid_model_route_credential`。已保存后停用、删除或失去模型资格的身份继续展示为不可用，可保留或移除；不会自动替换成其他凭据。
- 当前目录模型尚未配置路由时返回 `configured=false`、revision `0`、`entries=[]`；读取不会创建路由，数据面也不回退到旧候选发现。显式保存 `entries=[]` 表示停止该模型，保留 `configured=true` 和新的 revision，不代表“全部凭据”。V74 seed 的已配置路由也可能为 revision `0`，应结合 `configured` 判断。
- 列表顺序就是运行时尝试顺序，转换候选可以排在原生候选之前。同一身份的多个上游别名稳定、连续展开。渠道/凭据 `priority`、轮询、HRW、OAuth 策略都不重排；`prompt_cache_key` 只转发。
- 渠道与凭据启停、OAuth 鉴权、模型目录/授权、调用方访问范围、额度、账号级窗口/并发与冷却控制继续生效。故障转移和候选冷却 fail-open 只限已配置身份，不能补入未选择的凭据。
- V74 一次性为迁移时启用渠道的活跃公共模型建立路由，只 seed 当时已启用且满足模型/账号资格的凭据；后续新模型、新密钥和新 OAuth 账号都必须显式选择。优先级 API/存储字段仍兼容保留，运行时不使用它们排序。

### 3. 模型发现与协议探测

- **保存前发现上游模型**：
  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/model-discovery \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"baseUrl": "https://api.openai.com/v1", "credential": "sk-..."}'
  ```
- **探测渠道协议支持度**：
  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/probe-protocols \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"model": "gpt-4o-mini"}'
  ```
- **单模型快速连通性测试**：
  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/model-test \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"model": "gpt-4o-mini", "protocol": "chat_completions", "prompt": "请只回复 OK"}'
  ```

### 4. 模型检测

连通性、智商与指纹检测共用的可测试目标：`GET /admin/api/model-tests/targets`。返回值与
`/admin/api/iq-tests/targets` 相同，包含渠道、已授权模型、可用协议与凭据摘要。

#### 智商检测

- 题目定义：`GET /admin/api/iq-tests/benchmarks`
- 判分器列表：`GET /admin/api/iq-tests/judges`
- 可测试目标：`GET /admin/api/iq-tests/targets`
- 运行检测：`POST /admin/api/iq-tests`
- 人工判定：`PUT /admin/api/iq-tests/<id>/manual-verdict`
- 历史与详情：`GET /admin/api/iq-tests?limit=200`、`GET /admin/api/iq-tests/<id>`

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/iq-tests \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"channelId":"<channel-id>","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"local"}'
```

`benchmark` 支持 `pelican`（鹈鹕骑行）与 `candy`（糖果测试）。可选的 `prompt` 用于替换本次提示词，
留空时使用题目内置提示词；实际使用的提示词会随记录保存，最长 20000 个字符。鹈鹕题通过渠道已保存的凭据在本地生成
SVG/HTML，再由 `judgeId` 指定的判分器判分；省略时使用 `IQ_DEFAULT_JUDGE`。内置判分器为 `local`（本地结构检查 + 人工评分）
与 `manxue`（把 HTML 发送到满血AI 分类）。生成请求不写入请求日志，也不消耗调用方密钥额度。
糖果题只要求模型返回 `{"answer": <整数>, "reason": "<推导过程>"}`，由本地规则按 `answer` 是否等于 21 判分，
不调用判分器，`judgeId` 会被忽略。鹈鹕题的最终判定顺序为人工判定 > 判分器结果 > 本地结构兜底。
结果保留最近 200 次。详细说明见 `docs/iq-test.md`。

#### 指纹检测

- 参考库信息：`GET /admin/api/model-tests/fingerprint/info`
- 运行检测：`POST /admin/api/model-tests/fingerprint`
- 历史与详情：`GET /admin/api/model-tests/fingerprint?limit=100`、
  `GET /admin/api/model-tests/fingerprint/<id>`

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/model-tests/fingerprint \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"channelId":"<channel-id>","model":"claude-haiku-4.5","protocol":"messages"}'
```

一次运行生成三道随机数字题并并行请求上游，最长等待 4 分钟。`keyId` 省略时按渠道凭据优先级选择，
`protocol` 省略时使用渠道首选协议。响应包含判定（`scored` / `partial` / `not_model` / `unscorable`）、
68 个候选模型的分数与相对概率、逐样本诊断和原始回答。结果保存在 `fingerprint_test_runs`，保留最近 100 次；
单条回答最多保存 20000 字符。参考库随应用打包，比对过程不联网。详细说明见 `docs/model-detection.md`。

### 5. 冷却可见性与手动解除

- `GET /admin/api/channel-health?window=24h` 的每个渠道摘要包含 `cooldownUntil`、`cooldownReason`、`coolingCandidates` 和 `totalCandidates`，用于渠道列表展示冷却剩余时间与主因。
- 渠道摘要新增 `recent`（固定近 1 小时的 `from`、`to`、`metrics`、`state`、`reason`）以及 `recentAttempts`（所选窗口与最近 24 小时交集内、每渠道最近 30 次有效上游尝试，按完成时间、日志 ID、尝试序号升序）。客户端取消、跳过及其他排除项不进入趋势；每条包含 `logId`、`attemptIndex`、`createdAt`、`outcome`、`statusCode`、`errorCode`、`upstreamModel`、`elapsedMs`、`firstTokenMs`。原有 `metrics` 和 `state` 仍对应所选窗口，不改变统计口径。
- `GET /admin/api/channels/<id>/health` 的 `availability` 列表给出每个密钥/模型组合的状态、冷却原因和截止时间。
- 手动解除冷却：

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/channels/<channel-id>/cooldown/clear \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"scope": "channel"}'
  ```

  - `scope=channel`：清除该渠道下渠道键、密钥键和候选键的全部冷却。
  - `scope=key`：需同时提供 `keyId`，清除该密钥及其候选冷却。
  - `scope=candidate`：需同时提供 `keyId` 与 `upstreamModel`，只清除该候选。
  - 操作写入管理操作审计，动作为 `channel.cooldown.clear`。

### 6. 实时占用

`GET /admin/api/occupancy` 返回当前实例内存中的在途请求数，管理台按 5 秒轮询展示：

```json
{
  "sampledAt": 1762400000000,
  "channels": [
    {
      "channelId": "ch_...",
      "concurrentRequests": 3,
      "keys": [{ "keyId": "ck_...", "concurrentRequests": 2 }]
    }
  ],
  "clientKeys": [{ "clientKeyId": "ck_...", "concurrentRequests": 1 }]
}
```

- 渠道计数是该渠道下各渠道密钥在途尝试之和；只有大于零的条目会出现在快照中。
- 渠道密钥计数覆盖所有实际发往上游的尝试，与是否配置并发上限无关；它只用于展示，不改变限流判定。
- 调用方密钥计数从请求通过鉴权与额度检查开始，到响应结束、失败或客户端断开后释放。
- 计数只保存在单实例进程内存中，进程重启或切换实例后归零，不做跨实例聚合。

### 7. 订阅账号（OAuth）

订阅账号是独立的上游凭据实体：账号拥有自己的 id、refresh/access token、代理、兼容性优先级字段、模型授权、账号级窗口/并发策略与额度，**不再依附于任何渠道密钥**。账号优先级不改变模型请求顺序；数据面按公共模型的显式路由列表选择账号。

订阅凭据只能用于提供方自己的官方端点，因此端点由代码常量固定：网关为每个已实现上游协议的 provider 置备一条 kind=provider 的系统渠道，账号登录后自动关联到它，用户不再手工选择渠道。这类渠道不可编辑、不可删除，也不出现在渠道列表里。详细语义见 ADR 0016。

- 查看可用的 OAuth 提供方：`GET /admin/api/oauth-providers`，返回 `id`、`label`、`configured` 与 `managedChannel`。`configured` 表示网关已注册该提供方的 token 刷新器；`chatgpt` 与 `antigravity` 都内置公开客户端参数，默认即为 `true`，不需要部署方预先申请。两者也都内置授权端点与回调地址，可以直接发起浏览器登录；`managedChannel=true` 表示账号的端点渠道由系统维护。
- 维护官方模型列表：`PUT /admin/api/oauth-providers/{provider}/models`，请求体 `{"models":["..."]}`；更新系统端点渠道的活跃模型集合。已有手工 Canonical 映射保留，退出活跃集合的手工映射保留为休眠配置，不参与路由；旧列表保存不会直接重新激活已休眠的手工映射。
- 查看共享模型映射：`GET /admin/api/oauth-providers/{provider}/model-mappings`，返回提供方共享的映射、版本与受影响账号数；只读取本地配置，不请求上游模型目录，也不创建尚不存在的系统渠道。配置入口位于「订阅账号 → 账号设置 → 模型授权与映射」，详细契约见下文。
- 刷新账号自己的模型目录：`POST /admin/api/oauth-accounts/{id}/models/refresh`，目录按账号隔离并写回 `modelCatalog`，随后把系统端点渠道的模型收敛为各账号目录的并集。Antigravity 走 Code Assist 的 fetchAvailableModels（按当前 tier 与 `allowedTiers` 逐个尝试），ChatGPT 走 Codex 官方模型列表（`/backend-api/codex/models?client_version=...`，只保留 `visibility=list` 的模型）。目录请求与项目发现、额度查询一样经该账号的出口代理发出；账号代理无效时返回 `invalid_oauth_proxy`，账号没有可用 access token 时返回 `oauth_credential_unavailable`，上游没有返回可用模型时返回 `model_catalog_unavailable`。返回 `{"models":[{"id","name"}]}`。
- 账号看板：`GET /admin/api/oauth-accounts`，返回账号 `id` / `name`、provider、授权状态、是否启用、兼容性优先级、授权模型、关联渠道（`channelIds` 与 `channels[{id,name}]`）、token 到期时间、上游额度读数、账号级窗口/并发策略与实时资格状态。响应不包含任何 token，且禁止缓存。优先级不决定模型路由顺序；路由顺序由「模型路由」管理。
  - `models` 为空表示继承所关联渠道的全部模型。
  - `authState=REAUTH_REQUIRED` 的账号仍保留为候选，但凭据解析会返回 `subscription_account_unavailable` 并继续尝试其他候选；不会把 refresh token 发给上游。
  - `quota`：最近一次成功读取的上游额度快照，包含 5 小时与每周窗口的 `usedPercent` / `remainingPercent` / `windowSeconds` / `resetsAt`、`allowed`、credits 余额与可重置次数、`checkedAt` 与最近一次失败错误码。
  - `runtime.quotaBlockedUntil`：额度读数未过期且某个窗口已用尽时的预计恢复时间；此时新请求会被拒绝。
- 新建与重新授权账号：统一走浏览器登录（交互式授权），网关使用 PKCE + `state` 完成授权码交换，不需要先建渠道或密钥，也不需要手工粘贴 refresh token。

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-login/start \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"provider":"chatgpt","name":"新账号"}'
  ```

  返回一次性 `loginId`、`authorizeUrl`（10 分钟内有效）与 `redirectUri`。在浏览器打开 `authorizeUrl` 完成登录后，把浏览器最终停留的完整回调地址提交给网关：

  ```bash
  curl -b cookies.txt -X POST http://localhost:8080/admin/api/oauth-login/<login-id>/complete \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"callbackUrl":"http://localhost:1455/auth/callback?code=<code>&state=<state>"}'
  ```

  - `provider` 仅支持 `chatgpt` 与 `antigravity`；两者的客户端参数都内置，直接可登录。
  - 新建账号时不要传 `accountId`；对已有账号重新授权时传 `accountId`，可附带新的 `name` 与 `channelIds`。
  - `channelIds` 通常不需要传：新建账号登录后自动关联系统端点渠道。启动时仍会校验提交的每个 ID 必须存在，不存在返回 `400 invalid_oauth_channels`；`managedChannel=true` 的提供方在登录完成后的关联处理里忽略该字段。
  - `priority` 取值仍为 `1..1000`，默认 `1`，API 与存储继续兼容保留；它不参与模型请求排序，需通过「模型路由」调整已选择凭据的顺序。
  - `proxyUrl` 可在「账号设置」里配置，只支持无认证的 `http://主机:端口`；目标主机默认为本机回环地址，其他主机需要服务端配置 `OAUTH_PROXY_ALLOWED_HOSTS`，否则返回 `400 invalid_oauth_proxy`。重新授权时不改动已有代理。
  - `callbackUrl` 支持完整 URL、裸查询串或 `?查询串`，最长 4096 字符；必须是浏览器登录后真实产生的地址，不要手工拼接。
  - 登录会话只存在于内存，10 分钟有效；授权码只能交换一次，绑定成功后会话立即失效。过期或重复提交返回 `400 oauth_login_expired`，`state` 不匹配返回 `400 oauth_login_invalid_callback`。
  - 重新授权期间目标账号被删除或替换凭据时返回 `409 oauth_login_stale`，不会把授权结果写到新目标上。
  - 换取 token 失败返回 `502 oauth_login_failed`；提供方未配置授权参数返回 `400 oauth_login_unsupported`；邮箱已存在返回 `409 oauth_account_email_exists`，删除原账号后可用同一 `loginId` 重试，不会再次请求 token 端点。
  - 成功后账号立即写入关联渠道并清空冷却；响应不包含 token，长期凭据只以密文落库。

- 修改账号设置：`PUT /admin/api/oauth-accounts/<account-id>/settings`，原有 `{name, priority, enabled, proxyUrl, clearProxy}` 字段保持兼容；可同时提交可选 `models`、`modelMappingChanges` 和 `expectedModelMappingRevision`，一次事务保存账号设置、授权及提供方共享映射。省略的字段保持原值；`enabled=false` 后账号不再参与路由，管理台列表内可直接切换。
- 调整关联渠道：`PUT /admin/api/oauth-accounts/<account-id>/channels`，请求体 `{"channelIds":["..."]}`；整体替换关联集合。`managedChannel=true` 的提供方返回 `400 oauth_account_channel_managed`，关联由系统维护。
- 调整模型授权：`PUT /admin/api/oauth-accounts/<account-id>/models`，请求体 `{"models":["..."]}`；传空数组表示继承所关联渠道的全部模型。
- 删除账号：`DELETE /admin/api/oauth-accounts/<account-id>`；token、额度与调度状态一并删除，不影响渠道或静态密钥，并重算该提供方的活跃模型并集，保留手工映射配置。

#### 提供方共享的 Canonical 映射

`GET /admin/api/oauth-providers/{provider}/model-mappings` 的响应示例：

```json
{
  "provider": "chatgpt",
  "scope": "provider",
  "revision": 3,
  "affectedAccountCount": 2,
  "mappings": [
    {
      "upstreamModel": "upstream-model",
      "canonicalModelId": "team-chat",
      "publicModelId": "team-chat",
      "automaticCanonicalModelId": null,
      "mode": "manual",
      "mappingSource": "manual",
      "catalogActive": true
    }
  ]
}
```

- 映射身份是提供方端点渠道内的上游模型 ID。同提供方账号共享映射，但各自的目录和模型授权仍独立；不同上游模型共用同一个 Canonical ID 是合法的路由聚合。
- `publicModelId` 是实际对外请求和模型列表使用的 ID。设置 canonical 后，该映射不再接受原上游名作为对外请求 ID，转发仍使用 `upstreamModel`。
- `automaticCanonicalModelId` 表示目录的自动默认，null 表示自动沿用上游 ID。`mode` 是 `auto`、`manual` 或 `upstream`；`mappingSource` 表示来源，不替代模式。已有 `models_dev` 来源是显式确认的覆盖，不因同步或无关保存转换为自动；恢复自动后来源为 null。
- `catalogActive=false` 的手工配置仅为休眠映射，不参与模型曝光、路由或模型测试。再次同步发现相同上游 ID 后恢复原映射。
- 调用方密钥的模型白名单仍精确匹配当前对外 ID，并与渠道、凭据范围及账号自己的上游授权取交集。改变 Canonical 或切换映射模式不会自动改写调用方白名单；旧 ID 消失后返回 404，新 ID 未获准时返回 403。凭据维度使用 `credentialType` 区分静态密钥与 OAuth 账号，旧名单默认按静态密钥解释；休眠映射不会因白名单或未同步账号重新开放。
- 账号响应中的 `modelCatalog` 继续为上游 ID 字符串数组；刷新目录响应的 `name` 是展示名，不是 Canonical 配置字段。

通过账号 settings 接口保存增量映射，可与账号授权和其他设置一起原子提交：

```json
{
  "models": ["upstream-model"],
  "expectedModelMappingRevision": 3,
  "modelMappingChanges": [
    {
      "upstreamModel": "upstream-model",
      "mode": "manual",
      "canonicalModelId": "team-chat"
    }
  ]
}
```

- `modelMappingChanges` 只更新列出的模型；省略、null 或空数组不替换整个提供方目录，也不删除其他账号的模型。省略 `models` 不修改账号授权；显式 `models=[]` 仍表示全部。
- `mode=manual` 要求 trimmed Canonical ID 长度为 1..200；`mode=upstream` 强制使用上游 ID，`mode=auto` 恢复自动，这两种模式不能同时提交 canonical。
- 上游 ID 长度为 1..200，只能编辑已发现或已保存的映射；重复上游 ID、非法模式或未知模型会被拒绝，不能用映射编辑创建任意可路由模型。
- 共享映射变更要求提供 GET 返回的 revision；保存时版本不符返回 HTTP 409 `model_mapping_revision_changed`，账号设置、授权与映射全部回滚。客户端应保留草稿，刷新共享状态并核对后再提交，不自动重试覆盖。
- 成功响应保持为账号详情；重新 GET 共享映射取得最新 revision。模型目录同步保留手工映射，只更新自动默认及活跃状态。目录外显式授权仍按既有规则清理，全部落空时保留显式零授权状态，不扩大为全部模型。
- Canonical 候选可复用 `POST /admin/api/model-suggestions` 的 `canonicalId`，也可以自由输入；参考目录不可用或未收录该 ID 不阻止保存。

- 编辑账号级窗口/并发策略（资格限制，继续生效，不重排路由）：

  ```bash
  curl -b cookies.txt -X PUT http://localhost:8080/admin/api/oauth-accounts/<account-id>/policy \
    -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
    -d '{"windowLimit":40,"windowSeconds":10800,"maxConcurrentRequests":1}'
  ```

  `windowLimit` 为 `null` 表示不限制窗口；`windowSeconds` 取值 60..604800；`maxConcurrentRequests` 取值 1..1000。

- 手动刷新额度、刷新 token 与解除冷却：
  - `POST /admin/api/oauth-accounts/<account-id>/quota/refresh`：立即读取一次上游额度并写入快照；失败保留上一次成功读数，返回 `502 oauth_quota_failed`，账号不支持或没有可用 access token 时返回 `400 oauth_quota_unsupported`。
  - `POST /admin/api/oauth-accounts/<account-id>/refresh`：允许对 `REAUTH_REQUIRED` 账号再试一次；失败返回 `502 oauth_refresh_failed`。
  - `POST /admin/api/oauth-accounts/<account-id>/cooldown/clear`：清除账号级冷却，并清理该账号在所有关联渠道下的候选冷却。
- 以上写操作分别写入审计动作 `oauth_account.delete`、`oauth_account.settings.update`、`oauth_account.login.start`、`oauth_account.login`、`oauth_account.policy.update`、`oauth_account.quota.refresh`、`oauth_account.refresh` 与 `oauth_account.cooldown.clear`，只记录账号 id、provider、策略参数、渠道 id 与操作结果。
- V74 为迁移时当前模型和符合资格的凭据做一次性有序路由初始化；后续新增模型、静态密钥和 OAuth 账号都需在模型路由中显式选择。`OAUTH_ROUTING` 已弃用但仍接受原值，不再产生运行时效果。

---

### 8. 请求日志查询

`GET /admin/api/request-logs` 返回按记录 ID 倒序排列的日志数组，需管理员会话。所有筛选先在服务端执行，再应用游标与条数限制：

| 参数 | 语义 |
| --- | --- |
| `limit` / `beforeId` | 单页条数与上一页末条 ID；翻页时保持其他查询参数不变 |
| `from` / `to` | 毫秒时间戳，开始包含、结束不包含；不传表示全部保留日志 |
| `q` | 不区分大小写的字面子串，最长 2000 字符；覆盖请求与追踪 ID、模型、渠道与密钥名称、错误信息、IP 和 User-Agent；`%` / `_` 不作为通配符 |
| `channelId` / `model` | 渠道 ID 与请求模型精确匹配；`channelId=__unrouted__` 仅查询未路由请求 |
| `httpStatus` | 精确 HTTP 状态码、`2xx` 或 `4xx_5xx` |
| `outcome` | `success`、`recovered`、`failed`、`cancelled`、`other` 或 `abnormal`（失败与取消） |

返回的 `outcome` 区分最终请求结果与 HTTP 状态：HTTP 200 后发生流式错误仍为 `failed`；重试后成功为 `recovered`，冷却跳过不算上游调用；客户端取消或断开为 `cancelled`。

`GET /admin/api/request-logs/filter-options` 返回 `{channels, models, statuses}`，每项为 `{value, label}`。选项仅受 `from` / `to` 限制，覆盖该范围内的全部日志，不限于当前已加载页。名称优先使用当前配置，删除后回退到历史快照。

管理台默认最近 24 小时，并提供最近 15 分钟、1 小时、自定义时间与全部保留日志。自定义结束时间包含所选整分钟，界面显示浏览器本地时区。查看详情时暂停自动刷新，关闭后恢复原间隔。

---

### 9. 用量报表

`GET /admin/api/usage-report` 返回 `from` / `to` 毫秒时间戳范围内的请求、token、费用、趋势和维度聚合（当前接口两端均包含，最长 30 天）。可按 `clientKeyId`、`channelId`、`model`（上游模型）、`protocol` 精确筛选，并用 `timezoneOffsetMinutes` 指定趋势桶的本地时区偏移。

响应 `total` 新增：

| 字段 | 语义 |
| --- | --- |
| `successfulLatencySamples` | 有效成功总耗时样本数；没有样本时为 `0` |
| `p50TotalMs` / `p90TotalMs` / `p99TotalMs` | 成功总耗时的连续分位数，单位毫秒，可包含插值小数；没有样本时为空，按现有 JSON 配置省略字段 |

样本仅包含最终 HTTP 2xx、没有网关或上游错误码且总耗时非负的请求，包含重试后成功；失败、取消、中断及其他结果不参与。数据库按当前完整筛选条件聚合，不在前端估算。真实 `0 ms` 不视为空值；既有平均耗时的统计口径不变。

管理台的错误率为每个时间桶的错误数 / 请求数，无请求桶显示为空并断线；错误分布百分比以全部错误数为分母，前六类以外合并为“其他”。费用仍按 USD 返回原始十进制字符串；界面舍入不改变 API 或 CSV 精度，悬停、聚焦或点按金额可查看原始值。

---

## 三、调用方密钥生命周期与审计

### 1. 密钥创建与配额

`POST /admin/api/client-keys` 支持配置精细化限制：
- `requestsPerMinute`：按 UTC 固定分钟窗口限制单密钥每分钟请求数。
- `maxConcurrentRequests`：单密钥同时处理并发上限。
- `dailyTokenLimit`：按 UTC 自然日限制总 Token 数。
- `dailyUsdLimit`：每日费用上限（USD）。

### 2. 密钥轮换（Rotate）与永久撤销（Revoke）

- **原子轮换**：
  ```bash
  POST /admin/api/client-keys/<id>/rotate
  ```
  立即生成新明文与哈希，旧密钥即刻失效；新明文仅在响应中返回一次。
- **永久注销**：
  ```bash
  POST /admin/api/client-keys/<id>/revoke
  ```
  永久阻断此密钥所有请求并标记撤销时间，不可恢复，避免安全隐患。

### 3. 管理操作审计日志

```bash
GET /admin/api/audit-logs?limit=50&beforeId=<cursor>
```
记录所有管理写操作（POST / PUT / PATCH / DELETE）及系统自动停用事件，包含时间戳、操作人、接口动作、目标 ID、状态与来源 IP（GET 只读操作不入审计库）。
