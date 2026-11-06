# 订阅账号 OAuth 凭据、刷新与账号级调度

## 背景

渠道密钥此前只支持静态 API Key：`channel_keys.credential_*` 保存一份长期凭据，请求时解密后直接作为 Bearer / `x-api-key` / `x-goog-api-key` 发送。ChatGPT Free 账号与 Antigravity 使用 OAuth：长期 `refresh_token` 需要定期交换短期 `access_token`，刷新可能轮换 `refresh_token`，且每个账号必须绑定固定的出口代理。CONTEXT.md 与《路由与容灾机制》此前把订阅 OAuth 列为范围外，本 ADR 记录新增的数据模型与刷新语义。

## 决策

### 数据模型

- 新增 `channel_key_oauth_accounts`，与 `channel_keys` 一对一，随密钥级联删除。
- 长期 `refresh_token` 继续加密保存在 `channel_keys.credential_*`，不复制到新表。这样复用既有的 AES-256-GCM 加密、密钥启停四态、优先级、模型授权、主动限流、冷却和自动禁用机制；重新导入凭据等同于替换密钥凭据，会自然重置健康计数。
- 新表只保存短期 `access_token` 密文、到期时间、provider、账号邮箱/套餐、刷新诊断和账号级 `proxy_url`。
- `auth_state` 只有 `ACTIVE` 与 `REAUTH_REQUIRED`。`REAUTH_REQUIRED` 表示 refresh token 已失效（`invalid_grant`、解密失败等），只能由管理员重新导入；刷新失败不修改 `channel_keys.state`。
- `(provider, lower(account_email))` 唯一（邮箱非空时），避免同一账号被拆成多个密钥、重复计入上游滑动窗口。
- `access_token` 的密文、nonce、密钥版本和到期时间由检查约束保证同时存在或同时为空。

### 刷新语义

- `OAuthTokenService.resolveCredential` 是解析订阅凭据的唯一入口：OAuth 密钥取 access token，普通密钥解密静态凭据。OAuth 账号不可用时绝不回退到 refresh token。
- 已接入的路径：数据面转发（`ProxyRoutes` 的流式与非流式请求）、上游模型列表（`UpstreamModels`）、协议探测（`ProtocolProbe`）、渠道模型连通性测试（`ModelConnectivityTest`）、管理面模型发现对已保存密钥的解析，以及额度插件查询。渠道记录带 `oauthPolicy` 即视为订阅账号，静态密钥不再查询账号表。
- 调用方统一依赖 `KeyCredentialResolver`，由 `OAuthTokenService` 实现；额度查询在挂起上下文解析凭据，再把结果交给阻塞的插件线程，单个账号不可用时跳过并继续尝试其他账号。
- 数据面把账号不可用映射为可切换错误：`subscription_account_unavailable`（需要重新授权或 provider 未配置）按密钥长冷却，`subscription_account_refresh_failed` 按密钥短冷却，两者都会继续尝试其他候选；管理面路径没有候选可切换，统一返回 `upstream_credential_invalid`。
- 同一 `keyId` 的刷新按内存互斥锁串行，避免并发刷新打断 refresh token 轮换链。
- 请求路径懒刷新：缓存 token 未进入提前窗口时直接使用；进入窗口或解密失败时同步刷新。提前窗口取配置值与 token 寿命一半的较小值，短寿命 token 不会每次请求都刷新。
- 后台任务按 `access_token_expires_at` 扫描仍为 `ACTIVE` 的账号，最多 4 个并发刷新；`REAUTH_REQUIRED` 与未配置的 provider 不参与扫描。
- 刷新成功时，只有 `channel_keys` 中的凭据密文仍等于本次读取值才写入新 access token 和轮换后的 refresh token；管理员并发替换凭据时放弃本次结果。轮换 refresh token 不更新 `channel_keys.updated_at` 与 `state_version`，后台刷新不算管理员编辑。
- 失败分类：400/401/403 或 `invalid_grant` / `invalid_client` / `unauthorized_client` / `invalid_token` 视为终态；429、5xx、超时和网络错误可重试。库中只保存脱敏后的错误码，不保存响应正文或任何 token。

### 账号级调度

- `channel_key_oauth_accounts` 增加 `window_limit`（可空，空表示不限）、`window_seconds`（默认 10800 秒）与 `max_concurrent_requests`（默认 1）。窗口按账号统计，不区分模型，与上游订阅配额的作用域一致。
- `OAuthAccountScheduler` 在路由准入阶段执行三项检查：429 冷却、滑动窗口、单号并发。非 OAuth 密钥直接放行，不记录状态。
- 窗口名额在准入时消耗，不因本地中止而归还；宁可少用不超用。并发额度在请求结束时归还，取消或异常也会释放。
- 429 冷却优先使用 `Retry-After`，其次解析响应体中的 `resets_in_seconds`、`resets_at`、`retry_after*` 与 Google RPC `retryDelay`；没有提示时从默认 5 分钟开始按连续次数指数退避，最多退避到默认值的 16 倍。任何单次冷却都不超过 6 小时。
- 账号级窗口/并发/冷却属于硬限制，不进入 fail-open。当全部候选都被账号级限制拦下时返回 429 `subscription_account_rate_limited`，并带上最早可重试的 `Retry-After`。
- 内存态是权威来源，`channel_key_oauth_windows` 只保存重启恢复所需的快照（窗口命中、冷却截止时间、连续 429 次数）。快照按账号合并写透，最多每秒发布一次；关闭时强制刷新。
- 修改窗口策略不会重置已有命中；窗口缩小后旧命中按时间自然滑出，期间可能持续拒绝。

### 上游额度观测

- ChatGPT 提供 `/backend-api/wham/usage` 只读接口，返回 `rate_limit.primary_window`（5 小时）与 `secondary_window`（每周）的 `used_percent`、`limit_window_seconds`、`reset_at` / `reset_after_seconds`，以及 `credits` 余额与 `rate_limit_reset_credits.available_count`。`OAuthQuotaService` 每 5 分钟按账号扫描一次，最多 4 个并发。
- 读取走账号绑定的同一个代理出口，请求头与 Codex 客户端一致（`Authorization`、`chatgpt-account-id`、`originator`、`User-Agent`）。账号 id 优先取库中值，缺失时从 access token 的 `https://api.openai.com/auth` 声明解析并回写。
- 401 时强制刷新一次 access token 再重试；其余失败只记录脱敏错误码（`http_<status>`、`timeout`、`network_error`、`invalid_response`），保留上一次成功读数。
- 读数写入 `channel_key_oauth_accounts` 的 `quota_*` 列，并同步给调度器。`quota_checked_at` 为空或早于扫描间隔的账号才会被扫描；重启时从库中恢复读数，不必等第一轮扫描。
- 额度拦截条件：读数未过期（默认 15 分钟），没有 credits（`credits.balance == 0` 且非 `unlimited`），且 5 小时或每周窗口 `used_percent >= 100`。命中时拒绝新请求，`retryAfterMs` 取用尽窗口中较晚的重置时间，拒绝原因记为 `oauth_quota`。
- 读数过期后只展示、不再拦截，交给滑动窗口与 429 冷却兜底；上游额度接口异常不会把账号长期误停。
- 额度是账号级硬限制，与窗口/并发/冷却一样不进入 fail-open。

### 账号排序

- `OAUTH_ROUTING` 决定同一渠道、同一密钥优先级内的账号顺序，默认 `round_robin`，与原有档位轮询一致；`TierSharing` 先按渠道与密钥优先级分档，再在档内轮询或亲和，`OAuthAccountRouting` 只重排其中连续的 OAuth 账号段。
- `drain` 固定当前账号，直到它因额度用尽、窗口命中或冷却从候选列表中消失，再顺延到下一个可用账号；恢复后不回头抢占。指针按「渠道 + 密钥优先级」保存在内存，进程重启后从首个可用账号重新开始。
- `least_used` 取最近一次未过期的额度读数中两个窗口已用比例的最高值，升序排列；没有额度读数时退回本地窗口命中比例，两者都没有的账号排在已知账号之后。
- `pace` 取窗口跨度最长的一档（通常为每周窗口），用剩余比例除以距离重置的小时数，余量最高者优先；没有额度读数时用本地滑动窗口的剩余名额与最早命中到期时间估算。这会让即将过期且仍有余额的账号先用，而不是只看谁用得少。
- 排序不改变静态密钥的位置，也不跨优先级档位；客户端提供 `prompt_cache_key` 时保持亲和顺序，避免打散上游 prompt cache。

### Provider 与代理

- `OAuthTokenProvider` 只负责一次刷新请求，不读写数据库。ChatGPT 使用 Auth0 兼容的 JSON 请求体，client id 默认取 Codex CLI 的公开客户端并可由 `OAUTH_CHATGPT_CLIENT_ID` 覆盖；Antigravity 使用 Google OAuth 表单编码，只有显式配置 `OAUTH_ANTIGRAVITY_CLIENT_ID` 后才注册。
- 每个账号可绑定一个 `proxy_url`。刷新 access token 与后续调用上游必须使用同一个出口；当前只支持无认证的 `http://主机:端口`，Java HttpClient 不支持 SOCKS5，需要时在网关外桥接。
- 账号代理由管理员在导入时指定，与渠道 `base_url` 同属管理面信任边界：能配置代理的管理员本就可以改写上游地址。代理地址不允许携带账号密码，导入操作会把代理地址写入管理审计，便于追溯出口配置；不限制目标主机，否则会挡住住宅代理等合法用途。
- 刷新请求不跟随重定向，响应体限制 256 KB。

### 交互式登录

- provider 同时配置 `authorizeUrl` 与 `redirectUri` 时才开放浏览器登录（`loginSupported`）。ChatGPT 默认使用 Codex CLI 的授权端点与 `http://localhost:1455/auth/callback` 回调；回调主机只允许 `127.0.0.1`、`localhost` 或 `::1`，避免把授权码发往可能被他人接管的地址。
- 登录使用 PKCE（S256）与随机 `state`。会话只保存在进程内存，10 分钟过期，最多保留 64 个待完成会话；提交时用原子标记保证一次性使用。进程重启会丢失会话，重新生成即可。
- 回调地址支持完整 URL、裸查询串或 `?查询串`。授权码交换与手动导入共用同一个 `OAuthTokenProvider` 与账号代理配置；成功后写入同一张账号表、清空冷却，且不向管理面回显任何 token。登录发起、完成及失败原因都写入管理操作审计。

### 管理面

- 订阅账号与渠道密钥一对一绑定。导入/重新授权走 `PUT /admin/api/channels/{id}/keys/{keyId}/oauth-account`，只接受已配置的 provider；refresh token 必填，access token 与到期时间必须成对出现；代理地址复用刷新路径的校验，只允许无认证 HTTP 代理。
- 重新授权视为替换密钥凭据：加密写入 refresh token、清空旧 access token 缓存、重置刷新失败计数，并清空账号级冷却、密钥级候选冷却与自动禁用健康计数。密钥启停状态不变，人工停用与自动停用都需要管理员显式恢复。
- `GET /admin/api/oauth-accounts` 返回账号、渠道/密钥名称、策略、上游额度快照与实时调度状态（窗口用量、并发、冷却与额度拦截截止时间）；实时状态来自内存，未使用过的账号返回零值。响应禁止缓存，且不包含任何 token。
- 看板以进度条展示 5 小时与每周窗口的使用比例、重置时间与 credits；额度用尽时显示等待重置的剩余时间。
- 调度策略通过 `PUT /admin/api/oauth-accounts/{keyId}/policy` 单独编辑，重新导入凭据不会重置策略。
- `POST /admin/api/oauth-accounts/{keyId}/quota/refresh` 立即读取上游额度；失败返回 502 `oauth_quota_failed`，不支持或缺少 access token 时返回 400 `oauth_quota_unsupported`。
- `POST /admin/api/oauth-accounts/{keyId}/refresh` 允许对 `REAUTH_REQUIRED` 账号再试一次；失败按原因返回 502 `oauth_refresh_failed`。`POST .../cooldown/clear` 同时清除账号冷却与密钥冷却。
- 五个写操作都写入管理操作审计，只记录渠道/密钥 id、provider、账号代理、策略参数与操作结果，不记录任何 token。

## Consequences

- 管理面已提供手动导入/重新授权、浏览器登录、账号看板与调度策略编辑；浏览器登录依赖 loopback 回调，无法在纯远程部署中完成时仍可手动导入 refresh token。
- 数据面每个 OAuth 请求都会读取一次账号行以获取最新 `auth_state` 与 token 到期时间；静态密钥已在内存中区分，不受影响。若账号数量增长到数据库往返成为瓶颈，可在此基础上增加短 TTL 缓存。
- 账号级滑动窗口、单号并发、429 冷却与上游额度已经接入路由准入和失败结算；调度状态的内存态与单实例假设一致，多副本部署需要改为共享计数或按账号分片。
- 额度观测依赖上游私有接口。接口不可用或返回结构变化时解析失败，只影响额度展示与拦截，不影响 token 刷新与正常转发。
- 单实例假设不变：串行刷新依赖进程内互斥锁，多副本部署需要改为数据库租约或分布式锁。
- CONTEXT.md 与《路由与容灾机制》的范围边界已同步：订阅账号的浏览器登录、token 刷新、额度观测与账号池调度均已纳入。
