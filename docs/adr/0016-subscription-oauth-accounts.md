# 独立订阅账号：OAuth 凭据、刷新与账号级调度

## 背景

渠道密钥此前只支持静态 API Key：`channel_keys.credential_*` 保存一份长期凭据，请求时解密后直接作为 Bearer / `x-api-key` / `x-goog-api-key` 发送。ChatGPT 与 Antigravity 使用 OAuth：长期 `refresh_token` 需要定期交换短期 `access_token`，刷新可能轮换 `refresh_token`，且每个账号必须绑定固定的出口代理。

第一版实现把订阅账号挂在 `channel_keys` 上（`channel_key_oauth_accounts` 一对一），复用了密钥的凭据加密、启停、优先级与模型授权。但这也把账号生命周期绑死在渠道密钥上：必须先建渠道和密钥才能导入账号，删密钥就删账号，一个账号无法跨渠道复用，账号级排序也只能在同一密钥优先级内进行。本 ADR 记录将其彻底解耦后的数据模型与行为。

## 决策

### 数据模型

- 新增独立表 `oauth_accounts`：账号 id、名称、provider、账号邮箱/套餐、`refresh_token_*` 密文、`access_token_*` 密文与到期时间、`auth_state`、`enabled`、`priority`、代理、调度策略、额度快照与时间戳。`authorized_at` 记录 refresh token 的获取时间，只在重新授权时更新；短期 access token 的到期时间不能代表授权有效期（见 [V65](../migrations/V65-oauth-authorized-at.md)）。
- 新增 `oauth_account_channels`：账号与渠道的多对多关联。渠道只决定请求发往哪个上游端点，账号决定用哪个身份；同一账号可以同时服务多个渠道。
- 新增 `oauth_account_models`：账号显式授权的上游模型。没有记录表示继承所关联渠道的全部模型，便于新账号直接使用渠道模型目录。
- 新增 `oauth_account_windows`：账号级调度状态快照，随账号级联删除。
- 长期 refresh token 与短期 access token 都只以密文保存；`access_token` 的密文、nonce、密钥版本和到期时间由检查约束保证同时存在或同时为空。
- `(provider, lower(account_email))` 唯一（邮箱非空时），避免同一账号被拆成多个实体、重复计入上游滑动窗口。
- `auth_state` 只有 `ACTIVE` 与 `REAUTH_REQUIRED`；`enabled` 表达管理员启停。两者独立：停用不会丢弃凭据，重新启用即可恢复调度。`REAUTH_REQUIRED` 账号仍保留为候选，由凭据解析返回 `subscription_account_unavailable` 并继续切换，避免只有它支持某个模型时误报模型不存在。

### 数据面接入

- 数据面候选从「渠道密钥」扩展为 `UpstreamCredential`：`ChannelKeyRecord` 与 `OAuthAccountRecord` 都实现该接口。`RouteCandidate` 持有凭据而不是密钥，静态密钥与订阅账号共享渠道优先级、轮候、冷却与并发占用。
- `ChannelRecord.credentialsFor(model)` 合并静态密钥与关联账号，按 `priority`、名称与 id 稳定排序。账号的 `models` 为空时匹配所关联渠道的全部模型。
- 自动禁用、密钥级主动限流、探测租约等静态密钥专属能力只对 `ChannelKeyRecord` 生效；订阅账号由 `auth_state`、账号级窗口/并发/429 冷却与上游额度控制。
- `OAuthTokenService.resolveCredential` 是解析上游凭据的唯一入口：静态密钥解密 `credential_*`，订阅账号按账号 id 读取 access token。账号不可用时绝不回退到 refresh token。
- 已接入的路径：数据面转发（`ProxyRoutes` 的流式与非流式请求）、上游模型列表、协议探测、渠道模型连通性测试、管理面模型发现，以及额度插件查询。
- 数据面把账号不可用映射为可切换错误：`subscription_account_unavailable`（需要重新授权或 provider 未配置）按候选长冷却，`subscription_account_refresh_failed` 按候选短冷却，两者都会继续尝试其他候选。
- 同一账号的刷新按内存互斥锁串行，避免并发刷新打断 refresh token 轮换链。
- 请求路径懒刷新：缓存 token 未进入提前窗口时直接使用；进入窗口或解密失败时同步刷新。提前窗口取配置值与 token 寿命一半的较小值。
- 后台任务按 `access_token_expires_at` 扫描仍为 `ACTIVE` 且启用的账号，最多 4 个并发刷新；`REAUTH_REQUIRED` 与未配置的 provider 不参与扫描。
- 刷新成功时，只有账号行中的 refresh token 密文仍等于本次读取值才写入新 access token 和轮换后的 refresh token；管理员并发替换凭据时放弃本次结果。
- 失败分类：400/401/403 或 `invalid_grant` / `invalid_client` / `unauthorized_client` / `invalid_token` 视为终态；429、5xx、超时和网络错误可重试。库中只保存脱敏后的错误码，不保存响应正文或任何 token。

### 账号级调度

- `oauth_accounts` 保存 `window_limit`（可空，空表示不限）、`window_seconds`（默认 10800 秒）与 `max_concurrent_requests`（默认 1）。窗口按账号统计，不区分模型，与上游订阅配额的作用域一致。
- `OAuthAccountScheduler` 在路由准入阶段执行三项检查：429 冷却、滑动窗口、单号并发。静态密钥不进入该调度器。
- 窗口名额在准入时消耗，不因本地中止而归还；并发额度在请求结束时归还，取消或异常也会释放。
- 429 冷却优先使用 `Retry-After`，其次解析响应体中的 `resets_in_seconds`、`resets_at`、`retry_after*` 与 Google RPC `retryDelay`；没有提示时从默认 5 分钟开始按连续次数指数退避，最多退避到默认值的 16 倍。任何单次冷却都不超过 6 小时。
- 账号级窗口/并发/冷却属于硬限制，不进入 fail-open。当全部候选都被账号级限制拦下时返回 429 `subscription_account_rate_limited`，并带上最早可重试的 `Retry-After`。
- 内存态是权威来源，`oauth_account_windows` 只保存重启恢复所需的快照（窗口命中、冷却截止时间、连续 429 次数）。快照按账号合并写透，最多每秒发布一次；关闭时强制刷新。
- 修改窗口策略不会重置已有命中；窗口缩小后旧命中按时间自然滑出，期间可能持续拒绝。
- 账号级排序在同一渠道、同一优先级档位内进行，支持 `round_robin`、`drain`、`least_used` 与 `pace`。静态密钥保持原有位置；客户端提供 `prompt_cache_key` 时保持亲和顺序。

### 上游额度观测

- ChatGPT 提供 `/backend-api/wham/usage` 只读接口，返回 `rate_limit.primary_window`（5 小时）与 `secondary_window`（每周）的 `used_percent`、`limit_window_seconds`、`reset_at` / `reset_after_seconds`，以及 `credits` 余额与 `rate_limit_reset_credits.available_count`。`OAuthQuotaService` 每 5 分钟按账号扫描一次，最多 4 个并发。
- 读取走账号绑定的同一个代理出口，请求头与 Codex 客户端一致（`Authorization`、`chatgpt-account-id`、`originator`、`User-Agent`）。账号 id 优先取库中值，缺失时从 access token 的 `https://api.openai.com/auth` 声明解析并回写。
- 额度读数是调度提示而不是硬配额：窗口用尽且没有 credits 时，在读数有效期内拦截该账号；读数过期后只展示、不再拦截。上游回复头里的 `x-codex-primary-*` / `x-codex-secondary-*` 会即时合并进内存读数，比轮询更早发现用尽。
- 读取失败保留上一次成功读数，只记录脱敏后的错误码。

### Provider 与代理

- `OAuthTokenProvider` 只负责一次刷新请求，不读写数据库。ChatGPT 使用 Auth0 兼容的 JSON 请求体，client id 默认取 Codex CLI 的公开客户端并可由 `OAUTH_CHATGPT_CLIENT_ID` 覆盖；Antigravity 使用 Google OAuth 表单编码，内置 Antigravity 桌面客户端的公开 client id 与 client secret，可用 `OAUTH_ANTIGRAVITY_CLIENT_ID` / `OAUTH_ANTIGRAVITY_CLIENT_SECRET` 覆盖。两者都不需要部署方预先申请客户端。
- 每个账号可绑定一个 `proxy_url`。刷新 access token、额度查询与后续调用上游必须使用同一个出口；当前只支持无认证的 `http://主机:端口`，Java HttpClient 不支持 SOCKS5，需要时在网关外桥接。
- 代理地址不允许携带账号密码，操作会把代理地址写入管理审计，便于追溯出口配置。目标主机默认只允许本机回环地址（`127.0.0.1`、`localhost`、`::1`），住宅代理等远程出口必须由部署方通过 `OAUTH_PROXY_ALLOWED_HOSTS` 显式列入；导入、登录换 token、刷新 token 与额度查询都按同一份白名单校验。
- 刷新请求不跟随重定向，响应体限制 256 KB。

### 交互式登录

- provider 同时配置 `authorizeUrl` 与 `redirectUri` 时才开放浏览器登录（`loginSupported`）。ChatGPT 默认使用 Codex CLI 的授权端点与 `http://localhost:1455/auth/callback` 回调；Antigravity 使用 Google 授权端点与客户端登记的 `http://localhost:8085/callback`，并带上 `access_type=offline`、`prompt=consent` 以获取 refresh token。回调主机只允许 `127.0.0.1`、`localhost` 或 `::1`。
- 授权码交换按提供方补参数：ChatGPT 带 Codex 的 `originator` 身份头但不传 secret；Antigravity 传 `client_secret`（Google 安装型客户端必需）且不带 Codex 身份头。
- 登录使用 PKCE（S256）与随机 `state`。会话只保存在进程内存，10 分钟过期，最多保留 64 个待完成会话；提交时用原子标记保证一次性使用。
- 新建账号时 `accountId` 为空；对已有账号重新授权时传入 `accountId`，可同时更新名称与关联渠道。发起重新授权时记录账号 refresh token 密文的指纹，完成回调时若账号已被删除或替换凭据，返回 `409 oauth_login_stale`。
- 授权码只能交换一次。交换成功后 token 暂存在内存会话中；若绑定因邮箱唯一约束失败，管理员删除原账号后可用同一 `loginId` 重试，不会再次请求 token 端点。

### 管理面

- 账号是管理面的一等实体，不再要求先创建渠道或密钥。`POST /admin/api/oauth-accounts` 新建账号，`PUT /admin/api/oauth-accounts/{id}` 重新授权，`DELETE /admin/api/oauth-accounts/{id}` 删除账号。
- `PUT /oauth-accounts/{id}/settings` 修改名称、优先级、启停与代理；`PUT /oauth-accounts/{id}/channels` 整体替换关联渠道；`PUT /oauth-accounts/{id}/models` 修改模型授权，空数组表示继承渠道模型。
- `GET /admin/api/oauth-accounts` 返回账号、关联渠道、授权模型、策略、上游额度快照与实时调度状态；响应禁止缓存，且不包含任何 token。
- 调度策略通过 `PUT /admin/api/oauth-accounts/{id}/policy` 单独编辑，重新授权不会重置策略。
- `POST /oauth-accounts/{id}/quota/refresh`、`POST /oauth-accounts/{id}/refresh` 与 `POST /oauth-accounts/{id}/cooldown/clear` 分别用于手动读取额度、重试 token 刷新与解除冷却；解除冷却会同时清理该账号在所有关联渠道下的候选冷却。
- 写操作分别写入 `oauth_account.import`、`oauth_account.delete`、`oauth_account.settings.update`、`oauth_account.login.start`、`oauth_account.login`、`oauth_account.policy.update`、`oauth_account.quota.refresh`、`oauth_account.refresh` 与 `oauth_account.cooldown.clear` 审计动作，只记录账号 id、provider、渠道 id、策略参数与操作结果。

### 迁移

- V52 创建独立账号表与关联表，把旧 `channel_key_oauth_accounts` 的 `key_id` 复用为账号 id，把 `channel_keys.credential_*` 的 refresh token 搬到 `oauth_accounts.refresh_token_*`，同时迁移 access token、额度、策略、模型与窗口快照。
- 迁移完成后删除仅用于承载 OAuth 账号的 `channel_keys` 行，避免同一账号在数据面出现两次候选；静态密钥不受影响。
- 旧密钥的非 `ACTIVE` 状态统一映射为账号 `enabled=false`；自动临时停用的到期恢复不再沿用，需要管理员在账号页重新启用。升级前请按迁移说明核对处于 `AUTO_TEMPORARY` 的密钥。
- 旧表 `channel_key_oauth_accounts` 与 `channel_key_oauth_windows` 删除。历史请求日志中的 `channel_key_id` / `channel_key_name` 保留原值，仅作为审计信息。
- 账号级窗口与并发取代密钥级限流，`channel_key_rate_limits` 不迁移；升级、回滚与限流配置的差异见 [V52 订阅账号解耦升级说明](../migrations/V52-independent-oauth-accounts.md)。

### 端点渠道

- 订阅凭据只能用于提供方自己的官方端点，因此端点写成代码常量（ProviderEndpoints），不随渠道配置变化。
- 每个已实现上游协议的 provider 由 ProviderChannelProvisioner 置备一条 kind=provider 的系统渠道，账号导入或登录完成后自动关联；`oauth_account_channels.managed` 标出这条托管关联，每个账号最多一条，由部分唯一索引保证。
- 系统渠道不可编辑、不可删除，也不出现在渠道列表；账号页只展示上游端点。
- Antigravity 走 Google Code Assist 的信封协议（v1internal:streamGenerateContent），网关尚未实现，因此不置备渠道；它原有的手工渠道关联在 V54 中保留（`managed=false`），协议实现后由置备器收敛到系统渠道。
- V54 给迁移时已存在的账号打上 `provider_channel_pending`，启动对账逐账号改挂并清除标记；中途失败只需重启，不必重放迁移。迁移之后新建的可服务账号同样先标记待关联，挂到系统渠道后再清除；导入或登录在挂载前中断也能由启动对账修复。

### 端点渠道

- 订阅凭据只能用于提供方自己的官方端点，因此端点写成代码常量（ProviderEndpoints），不随渠道配置变化。
- 每个已实现上游协议的 provider 由 ProviderChannelProvisioner 置备一条 kind=provider 的系统渠道，账号导入或登录完成后自动关联；账号与渠道的一对一关系由唯一索引保证。
- 系统渠道不可编辑、不可删除，也不出现在渠道列表；账号页只展示上游端点。
- Antigravity 同样由置备器建渠道；它的上游协议由 Code Assist 信封承载，账号导入后同样自动关联，见下节。

### Antigravity 与 Code Assist

- Antigravity 的订阅通道是 Google Code Assist，不是 Gemini API：请求与响应都多一层信封，
  认证用 `Authorization: Bearer`，project 与 tier 按账号发现。
- 网关在 HTTP 边界包装/解包信封，内部仍按 Gemini 处理，协议转换、用量统计与日志不受影响。
- 模型目录按 tier 试错拉取，变体 id 收成家族后按思考等级选择；系统提示中的少数拒绝行
  用零宽空格规避；403 封禁按两小时账号级冷却处理。
- 细节与来源（magpie 的 `google.go` / `codeassist.go`）见
  [Google Code Assist](../codeassist.md)，升级说明见
  [V60](../migrations/V60-oauth-upstream-project.md)。

## Consequences

- 订阅账号可以跨渠道复用，删除渠道不再删除账号；账号池调度、额度与代理都按账号聚合。
- 账号不再继承静态密钥的自动禁用规则与密钥级主动限流；账号可用性由 `auth_state`、账号级窗口/并发/429 冷却与上游额度控制。
- 数据面每个 OAuth 请求仍会读取一次账号行以获取最新 `auth_state` 与 token 到期时间；静态密钥不受影响。若账号数量增长到数据库往返成为瓶颈，可在此基础上增加短 TTL 缓存。
- 账号级调度状态仍是单实例内存态，多副本部署需要改为共享计数或按账号分片。
- 额度观测依赖上游私有接口。接口不可用或返回结构变化时解析失败，只影响额度展示与拦截，不影响 token 刷新与正常转发。
- 串行刷新依赖进程内互斥锁，多副本部署需要改为数据库租约或分布式锁。
