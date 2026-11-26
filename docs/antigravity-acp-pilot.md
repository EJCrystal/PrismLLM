# Antigravity 订阅：官方 CLI 接入试点

目标是继续使用自己的 Antigravity 订阅，不切换到 Google AI Studio 的 Gemini API。两者的授权和配额独立；不需要为试点提供 Gemini API 密钥，也不把旧网关的 OAuth 凭据导入 CLI。

## 接入边界

- PrismLLM 通过本地适配器启动官方 `agy acp`，在标准输入输出上使用 ACP。Google 登录、凭据存储、续期和上游请求都由 `agy` 处理；PrismLLM 不读取或保存 CLI 的凭据。
- 首轮仅限自己的账号和独立运行环境。使用专用配置目录和空工作目录，不挂载仓库、网关数据或其他用户文件。不加载外部工具或 MCP 服务，不继承 API Key 或其他鉴权设置，使用 CLI 的 `Log in with Google` 登录方式。
- ACP 是 agent 协议，不是无工具的模型 API。试点客户端不提供文件或终端能力，并拒绝所有权限请求；这不能限制 CLI 自带的工具。先在 CLI 设置和运行环境限制工具，只发送受控的验收文本。没有验证工具隔离前，不接收外部调用方的任意提示词。
- 首轮只支持单会话、单并发的文本流，不加入订阅账号池，不提供工具、文件、图片或跨请求复用会话。不回退到旧直连、Gemini API 或付费渠道。

## 实现和验收

1. **无账号验证（已完成）**：在断网沙盒中启动官方 Linux `agy` 1.5.15；发行包 SHA-512 与官方清单一致。`initialize` 成功协商 ACP v1，返回 `oauth-personal`（`Log in with Google`）。未登录的 `session/new` 返回 `-32000`（`Authentication required`）。这确认了进程可启动、协议可通信，不代表订阅或生成已经可用。
2. **传输适配（模拟验收通过）**：内部 `AntigravityAcpPilot` 每次启动独立子进程，依次执行 `initialize`、`session/new`、`session/prompt`，只转发当前会话的 `agent_message_chunk` 文本。一个实例同时只执行一轮，不继承父进程的环境变量，不记录原始帧或自行推算费用。组件用例覆盖文本流和过滤、权限拒绝、断开和超时发送 `session/cancel`、子进程挂起或退出、流中错误、模型枚举及精确选择，以及完整验收入口。模拟验收不代表真实订阅或生成已经可用。
3. **实号功能验证（新账号已通过）**：账号持有人在独立运行环境执行 `agy acp --login`，在 Google 页面完成授权，验证码只提交给 CLI。2026-11-26 换号后，保持当前模型 `gemini-4-hydrogen`，短文本流、取消确认和重新启动进程后的登录复用验收通过；同日 13:42 已通过当前 CLI 会话的官方 Pro 档位核验，14:16 又通过零生成请求的令牌自然到期刷新验收。不启用 API Key 或额外付费，额度不足就停止，不切模型或回退。
4. **隔离接入（实现完成，默认关闭）**：为指定的受控调用方提供独立的 OpenAI 风格文本入口，不加入普通路由候选池。每轮执行前检查成功验收记录、固定模型、CLI 完整性和只读策略，失败即停止。不自动替换模型，不部署旧直连的退役改动。

## 验收矩阵与剩余事项

下表区分真实订阅证据与不消耗订阅额度的自动回归。模拟 ACP 的通过不能替代官方 CLI 实号结果；历史报告保留当时状态，以各专项报告补齐证据，不回写旧成功守卫。

| 验收项 | 当前证据与范围 |
| --- | --- |
| 官方 CLI 完整性、独立 home、受限网络、禁用额外额度 | 已有实机隔离检查与 Python 回归；仅 Linux x86_64 同用户试点 |
| 固定模型的文本、完整 SSE、真实 TCP 断线取消、登录复用 | 已有隔离 HTTP 实号报告 `reports/http-verification.json`；固定 `gemini-4-hydrogen` |
| 生产数据库专用密钥鉴权与清理 | 13:24 实号验收通过，十三项鉴权检查、一次短生成；两条自有记录撤销删除，无残留，不再重复执行 |
| 当前 CLI 账号官方 Pro | 13:42 官方 ACP 当前会话档位通过；不使用另一个浏览器账号的证据 |
| 自然到期后由官方 CLI 刷新并继续登录 | 14:16 专项实号报告通过；明确授权后仅提取到期时间，零生成，不改凭据或时钟 |
| 完整应用启动、数据库鉴权、Netty HTTP、ACP 子进程联动 | `AntigravityAcpApplicationTest`：临时 PostgreSQL、真实 loopback TCP、模拟 ACP，调用实际配置命令与 `GatewayApplication.install`；不接触实号或生产库 |
| 默认关闭、普通路由不变、失败不回退 | 同一测试应用配置同名普通模型，验证 ACP 关闭时 404、开启时独立鉴权，以及额度/停用/模型冲突不会转发普通上游；路由记录与无关调用方记录保持不变 |
| 流式/非流式、密钥轮换撤销、断线后复用、停机 drain | 完整应用模拟端到端回归；验证取消确认、准入释放、拒绝新工作和监听器关闭，不编造 usage |

剩余事项按优先级推进，当前不属于上线许可：

1. **目标部署形态的组合实号冒烟**：当前实号证据来自隔离 HTTP/数据库专项入口，完整应用联动使用模拟 ACP。尚未把完整应用、目标启动方式和官方 CLI 在同一候选发布环境组合实号演练；仅在确有发布候选且单独确认后执行最小短生成，复用既有登录与证据，不重跑全部历史验收。固定模型和订阅额度约束不变，额度不足立即停止。
2. **候选部署的故障恢复演练**：在独立状态目录和测试库中验证进程异常退出、受限代理中断、宿主重启后的锁及孤儿进程回收，先用模拟 agent；不可把已有超时/正常 drain 的通过等同于已完成 SIGKILL 或宿主重启验收。不得终止其他会话进程、修改真实凭据或生产服务。
3. **部署与回退操作单**：尚未交付容器镜像、远程 sidecar 或生产发布。目标机器需确认用户/权限、Python/Java/Bubblewrap 路径、状态卷和 loopback 代理可达性；关闭试点只能撤去专用入口或关闭开关，不得转回旧直连、其他模型、API Key 或额外付费。
4. **完整仓库回归基线**：定向 ACP 回归不等于全仓通过。源实验工作区中另有旧直连退役改动，曾造成 `TestClassificationTest` 仍登记已删除的 `CodeAssistIntegrationTest`；本提交不包含这些退役变更，保留原有测试和线上路径，不通过修改分类表掩盖该问题。提交分支需独立验证测试分类与原有 Code Assist 路径，不能沿用混合实验工作区的结果作为完整仓库通过的证明。

多账号池、并发扩容、任意外部提示词、工具/文件/图像、多轮会话和 token/USD 精确结算均为明确不支持，不列为“已验收”。试点继续默认关闭；未改线上路由、部署或开放公共服务。

## 一键实号验收入口

当前入口仅支持 Linux x86_64，使用 Python 3.11+、Bubblewrap、Java 21 和 Maven。默认借助本机 `http://127.0.0.1:7897` 的 HTTP 代理访问官方服务；代理不可用时停止，不自动改为直连。可用入口的 `--proxy` 参数指定另一个无凭据的本机 HTTP 代理端口。

在仓库根目录执行：

```bash
python3 scripts/antigravity_acp_pilot.py prepare
```

`prepare` 会完成以下步骤，不要求账号，也不发起模型生成：

- 下载固定的官方 `agy` 1.5.17，校验发行包 SHA-512，再安装到专用私有目录；校验值和来源保存在 `scripts/antigravity-acp-release.json`。不执行远程安装脚本，不修改系统 `PATH` 或现有 Antigravity 配置。
- 创建私有 home、空工作目录和生成时只读的配置：`useG1Credits: false`、默认 Google 登录提供方、严格工具权限及工具拒绝规则。CLI 日志指向 `/dev/null`，关闭遥测和默认自定义扩展。
- 验证文件、进程和网络命名空间。沙盒不挂载仓库、宿主 home、数据库或 Docker socket；不继承 API Key、宿主钥匙串或 D-Bus 会话。
- 沙盒仅通过专用 Unix socket 和受限 HTTPS 代理访问允许的 Google OAuth/Code Assist 域名。API Key 使用的 `generativelanguage.googleapis.com`、宿主回环端口和其他域名不可达。代理只转发 TLS，不读取登录数据。该环境仍只用于受控试点，不是面向任意提示词的通用隔离执行服务。
- 构建独立 JVM 验收入口，启动无账号 ACP 预检。不会启动网关、访问网关数据库、注册路由或部署候选退役改动。

默认状态目录为 `~/.local/state/prismllm/antigravity-acp-pilot`（目录 0700、文件 0600），不在仓库中。可通过 `--state <绝对路径>` 指定另一个独立私有目录。凭据由 CLI 自己写入专用 home；普通准备、登录和网关运行入口不读取、导出或打印凭据。只有明确授权的独立自然到期验收可只读取到期时间，限制见下文；不提取或导出 token。`reports/ready.json` 出现 `READY_FOR_GOOGLE_LOGIN` 只表示环境已准备好，不表示已经登录或可以生成。

### 账号持有人登录

在本机终端执行下列命令，按 CLI 提示选择持有订阅的个人 Google 账号。只在 CLI 指定的 Google 页面和本机终端完成授权，不把验证码、完整回调或凭据发到聊天。

```bash
python3 scripts/antigravity_acp_pilot.py login
```

登录子命令只执行官方 `agy acp --login`，允许 CLI 更新自己的专用设置，退出时重新写入禁用额外额度的试点配置。生成进程使用只读策略，不允许切换到额外额度或 API Key 提供方。登录成功之后仍需核对官方订阅页面中的账号和档位；普通账号也可能具有基础访问资格，不能以能登录或能回答代替订阅权益核对。

### 查看模型与验收

```bash
python3 scripts/antigravity_acp_pilot.py probe
python3 scripts/antigravity_acp_pilot.py verify --model <probe返回的精确模型ID>
```

`probe` 创建真实 ACP 会话、只打印经过校验的模型 ID，不调用生成。`verify` 要求所选模型确实出现在当前会话，按返回的模型配置显式选择；模型缺失、切换失败或额度不足时停止，不替换模型、不自动重试或回退。

验收包括：随机标记短文本生成、收到文本后取消另一轮、再次启动进程并确认登录可复用。只有 agent 对原 `session/prompt` 回复 `cancelled` 才记为取消已确认；只发出了通知、进程被结束或模型已经自然完成，都不能代替确认。报告写入私有目录中的 `reports/probe.json` 和 `reports/verification.json`，不包含原始 ACP 帧、提示词、回答正文或凭据。

`REAL_ACP_ACCEPTANCE_PASSED` 是功能报告，其中 `subscriptionEntitlementVerified` 和 `refreshAfterExpiryVerified` 仍为 `false`，不把生成或进程重启当作档位与到期续期证明。最新官方档位核验以独立的 `reports/account-verification.json` 为准，不回写或破坏原功能验收守卫。可用模型以实际账号和 ACP 客户端限制为准，不保证自建客户端能使用所有第三方模型。

## 自动回归

从仓库根目录复验模拟子进程（依赖已缓存时可加 `--offline`）：

```bash
mvn --batch-mode --no-transfer-progress -pl prism-app -am \
  -Dtest=AntigravityAcpPilotTest,AntigravityAcpConfigTest,AntigravityAcpGatewayTest,AntigravityAcpHttpAcceptanceTest,AntigravityAcpDatabaseAcceptanceTest,AntigravityAcpApplicationTest,GatewayConfigTest,GatewayMetricsTest,RequestDrainTest,RequestLimitServiceTest,RequestAuthenticationIntegrationTest,TestClassificationTest,CodeAssistIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

准备和隔离策略的本地测试不访问真实 Google 账号或网络：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_antigravity_acp_*.py' -v
```

`AntigravityAcpApplicationTest` 用独立的模板克隆测试库和随机回环端口启动完整应用。生产配置的子进程命令仍被实际调用，但脚本被替换为临时测试启动器，执行模拟 ACP 子进程，不启动官方 CLI。HTTP 出站使用 MockEngine：模型价格目录和普通路由都返回本地模拟结果，任何其他目标使测试失败。数据库、调用方密钥和登录标记均为临时合成数据，测试结束关闭自有服务器及连接池；不从环境变量读取生产数据库配置、API Key 或私有试点目录。

工作流 `Antigravity ACP isolation regression` 在空环境变量和临时 home 中执行上述 Python 回归；不安装官方 CLI，不使用账号、密钥、Google 网络或生成额度。Kotlin 的完整应用与兼容性回归沿用仓库现有 Maven 测试流程。

参考：[Google Antigravity CLI 的 ACP 说明](https://antigravity.google/docs/cli/acp)、[CLI 设置](https://antigravity.google/docs/settings)、[ACP 会话规范](https://agentclientprotocol.com/protocol/session-setup)。

## 2026-11-26 实号验收记录（首次账号，服务停用）

- 隔离环境中的 Google 登录可复用，ACP 建会话和模型目录读取成功；这些结果不等于订阅档位或生成权益已验证。
- 从返回目录精确选择 `gemini-3.8-flash`，只提交了一轮受控短文本验收。额外 AI credits 保持关闭，未启用 API Key，没有切换模型、回退或重试。
- 官方 CLI 保存的本轮诊断包含 HTTP 403、`This service has been disabled in this account` 和 `violation of Terms of Service`。该结果是服务停用，不是已确认的额度不足；仅凭这条诊断不能判断停用发生时间或归因于本轮验收。
- 实号调用已停止；文本生成未通过，取消、进程重启后的登录复用及到期刷新均未继续验收。需账号持有人先在官方 Antigravity 中确认该服务状态，恢复前不再测试。
- 验收入口现将已知服务停用和 `RESOURCE_EXHAUSTED` 等额度错误归类为脱敏状态，立即终止，不把 CLI 输出的错误当作成功回答。报告不记录正文、凭据或原始帧。

## 2026-11-26 换号后验收（功能通过）

- 账号持有人确认旧账号被禁用后，旧私有环境已归档；当前环境重新创建并通过隔离检查，没有复制旧凭据。新账号由持有人自行通过官方 CLI 登录。
- 新账号 ACP 建会话及模型目录读取成功；全程固定目录返回的当前模型 `gemini-4-hydrogen`，没有改用 Flash、Claude 或其他模型。
- `REAL_ACP_ACCEPTANCE_PASSED`：随机标记短文本生成匹配，收到 2 个文本片段；第二轮收到文本后取消，原 `session/prompt` 明确回复 `cancelled`；再次启动官方 CLI 后成功建立会话，登录可复用。
- 额外 AI credits 保持关闭，使用 Google 登录，不使用 API Key。仅完成上述两轮受控文本验收，没有重试、切模型或付费回退，结束后未追加生成请求。
- `subscriptionEntitlementVerified: false`：功能通过不代表已核对官方订阅档位；`refreshAfterExpiryVerified: false`：进程重启后的登录复用不等于已验证令牌到期刷新。
- 私有目录的 `reports/verification.json` 保存脱敏结果（0600）。尚未启动网关、注册试点路由、接入账号池、迁移线上路由或部署旧直连的退役改动。

## 独立网关入口

入口默认关闭，不改变现有 `/v1` 或 `/v1beta` 路由。启用前必须完成上述实号验收；登录和验收与网关生成共享同一个排他锁，运行中的请求不会与重新登录并发修改 CLI home。开始重新登录或重新验收会删除旧成功报告；失败后不能继续凭旧报告放行。网关模式不下载 CLI、不发起登录、不读取凭据，也不切换模型。

仅适用于 Linux x86_64 的同一主机、同一操作系统用户、已准备好的专用私有状态目录；尚未提供容器镜像、远程 sidecar 或多账号调度。不要直接把此受控试点开放为面向任意用户的公共服务。

### 启用配置

在计划启动的网关环境中显式设置以下变量（示例路径需改成该机器的实际绝对路径）：

```dotenv
ANTIGRAVITY_ACP_ENABLED=true
ANTIGRAVITY_ACP_SCRIPT=/absolute/path/to/PrismLLM/scripts/antigravity_acp_pilot.py
ANTIGRAVITY_ACP_STATE_DIR=/absolute/path/to/private/antigravity-acp-pilot
ANTIGRAVITY_ACP_MODEL=gemini-4-hydrogen
ANTIGRAVITY_ACP_CLIENT_KEY_IDS=00000000-0000-0000-0000-000000000001
ANTIGRAVITY_ACP_PROXY=http://127.0.0.1:7897
ANTIGRAVITY_ACP_TIMEOUT_MS=120000
```

- `CLIENT_KEY_IDS` 填写现有调用方密钥记录的 UUID，不是明文密钥。仍使用正常的 Bearer 密钥鉴权，校验撤销、启用和到期状态；额外要求 UUID 在上述名单中。
- 首版只向可信、受控提示词的调用方开放。普通调用方不会自动获得访问权；密钥的模型白名单/黑名单照常限制。带有渠道或渠道凭据限制的密钥被拒绝，避免绕过它们的原有访问范围。
- ACP 没有可靠的 token/费用用量，因此不接受带每日 token 或每日 USD 限额的密钥，不编造 usage 或以零费用结算；RPM 和调用方并发限制继续使用网关已有准入服务。本入口也不接入普通请求的价格结算、持久用量和渠道健康大盘，不能把缺失统计解读成已验证的零用量或零成本。
- 一次仅允许一个 ACP 请求；占用时直接返回 429，不排队、不轮换账号、不重试。独立 Python 包装进程持续持有状态锁，另一个网关或登录占用同一状态目录时同样拒绝执行。
- 仅允许固定的、实号验收记录里的模型；若新 ACP 会话的当前模型不同则在发送提示词前返回 409，不调用 `session/set_model` 或 `session/set_config_option`。

### 调用约定

- `GET /antigravity/v1/models`：返回唯一配置的模型，不启动 CLI、不消耗模型生成额度。这是配置目录，不是实时额度或订阅权益证明。
- `POST /antigravity/v1/chat/completions`：只接受 `model`、一条 `role=user` 且 `content` 为非空字符串的 `messages`，以及可选布尔 `stream`。文本最多 32768 字符；不支持历史消息、system 消息、工具、图片、文件、采样参数或其他字段，遇到不支持的选项返回 400，不静默忽略。

```json
{
  "model": "gemini-4-hydrogen",
  "messages": [{ "role": "user", "content": "只回复 OK，不使用工具。" }],
  "stream": true
}
```

使用 base URL `http://127.0.0.1:<网关端口>/antigravity/v1` 和专用调用方 Bearer 密钥。非流式返回 Chat Completions 文本响应；流式返回 OpenAI 风格 SSE chunks 和 `[DONE]`。不返回推理块、工具内容、凭据或虚构的 usage。

额度耗尽或限流时非流式返回 429；服务停用返回 403；超时返回 504。SSE 响应头发出后无法改 HTTP 状态，此时发送标准 error 数据帧并结束，不发送成功的 finish_reason。不切其他模型、旧直连、Google AI Studio/API Key 或任何付费渠道。策略固定 `useG1Credits: false`；生成网络仍只允许 Google 登录/Code Assist 域名，不开放 API Key 模型端点。Chat 响应均带 `x-should-retry: false`；调用方也应关闭 SDK 自动重试和自身的渠道/模型回退，不能把网关不重试当作客户端不会重试的保证。

HTTP 客户端断线、请求超时和网关停机取消会向当前 ACP 会话发送 `session/cancel`，等待取消确认后关闭进程；CLI 不响应时在限时内终止进程，释放准入和私有目录锁。每次请求重启独立 CLI，复用的是专用 home 中的登录，不是上轮对话或工作目录；不会因此重新发起 Google 登录。HTTP 功能验收本身不证明到期刷新；该证据已由 2026-11-26 14:16 的独立自然到期实号验收补齐，见 `reports/natural-expiry-verification.json`，不修改原 HTTP 报告。

调用方密钥轮换或撤销影响后续请求的数据库鉴权，不主动中断已经获准的在途请求；需要停止在途工作时使用 HTTP 断线取消或受控 drain。轮换不改变白名单记录 UUID，也不重新登录 CLI。

自动化 HTTP 和断线回归测试使用模拟 CLI，不消耗实号额度。构建和测试不代表已经部署；真实 HTTP 验收须显式运行下列独立入口。

### 专用密钥隔离 HTTP 实号验收

先完成专用 home 的登录和同一模型的 ACP 验收，保留其成功的 `reports/verification.json`。构建可执行包后，从仓库根目录手动执行：

```bash
java -cp prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar \
  dev.soven.prismllm.antigravity.AntigravityAcpHttpAcceptanceMainKt \
  "$PWD/scripts/antigravity_acp_pilot.py" \
  "$HOME/.local/state/prismllm/antigravity-acp-pilot" \
  gemini-4-hydrogen
```

该入口只在 `127.0.0.1` 的临时端口挂载真实的 ACP HTTP 路由和网关运行时，不构造常规应用、不连接数据库、不运行迁移或后台任务，也不修改现有线上路由。调用方密钥在内存中随机生成，绑定唯一 UUID 和固定模型，限制单并发及每分钟三次请求；不写入生产密钥表，不打印或持久化明文密钥。鉴权和额度读取使用隔离的内存适配器，因此结果不代表已经验收生产数据库的密钥管理。

验收先确认常规路径不存在、未携带密钥会被拒绝，再按顺序发出至多三次生成 HTTP 请求：非流式随机标记、完整 SSE 随机标记、收到首段文本后断开 TCP 连接。取消必须由原始 ACP prompt 返回 `cancelled` 确认，不能以断开连接或杀进程代替。最后只创建会话、不生成，确认取消后仍能复用原登录和固定模型。每一步失败都停止后续实号检查，包括额度不足、账号停用、模型不匹配、响应异常及取消未确认；不重试、不切模型，不使用额外额度、API Key 或付费回退。

结束时撤销临时内存密钥并通过 HTTP 验证拒绝访问，关闭临时监听器、释放请求并发并检查子进程已退出；保留官方 CLI 登录。脱敏报告保存为 `reports/http-verification.json`，权限 0600，不覆盖网关启动守卫使用的原 ACP 验收报告。通过状态为 `REAL_HTTP_ACP_ACCEPTANCE_PASSED`；官方订阅档位与令牌到期刷新仍不在此验收范围内。

### 生产数据库专用密钥验收

该入口补充真实 PostgreSQL 密钥仓储与生产共用鉴权函数的集成验证，不代表把 ACP 部署到线上。它只启动独立的 loopback 临时 HTTP 监听器，不创建常规应用、不运行迁移、种子或后台任务，不修改渠道、路由或现有调用方密钥。

先核对数据库容器、共享网络别名、回环映射端口和数据库名，确认连接的是预期生产实例。仅通过受控进程环境注入以下变量，不把数据库口令写进命令行、仓库或报告：

| 变量 | 约束 |
| --- | --- |
| `PRISM_ACP_ACCEPTANCE_DATABASE_URL` | `jdbc:postgresql://127.0.0.1:<port>/<database>`，不允许 URL 凭据、查询参数或远程地址 |
| `PRISM_ACP_ACCEPTANCE_DATABASE_USER` | 已核对目标实例的数据库账号 |
| `PRISM_ACP_ACCEPTANCE_DATABASE_PASSWORD` | 由受控环境传入，不回显 |
| `PRISM_ACP_ACCEPTANCE_DATABASE_CONFIRMED` | 明确设为 `true`，表示已确认生产目标及本次专用记录的写入范围 |

执行前必须保留同一模型的成功 `reports/verification.json`，并确认下节的最新 `account-verification.json` 中 `canContinueAcceptance` 为 `true`、模型相同。任何额度不足、服务停用或鉴权失败都停止，不凭旧报告放行。构建包后显式执行：

```bash
java -cp prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar \
  dev.soven.prismllm.antigravity.AntigravityAcpDatabaseAcceptanceMainKt \
  --production-database \
  "$PWD/scripts/antigravity_acp_pilot.py" \
  "$HOME/.local/state/prismllm/antigravity-acp-pilot" \
  gemini-4-hydrogen
```

写入范围仅限本次生成、名称为 `acp-acceptance-<UUID>` 的两把专用密钥及其访问策略。密钥有十分钟有效期、单并发、每分钟一次请求和固定模型限制；明文只存在于验收进程内存，不打印、不持久化。第二把密钥只用来确认数据库中的有效密钥不能越过 ACP 专用 ID 白名单。使用最多两个数据库连接，单条数据库语句超时三秒。

验收覆盖常规路由不存在、缺失/未知密钥、非白名单密钥、有效/禁用/到期密钥、模型及渠道访问策略、不可可靠结算的每日 token 限额、轮换前后密钥与撤销状态。上述鉴权请求不启动模型生成；随后仅允许一次固定模型的短文本非流式请求，失败立即停止，不自动重试、不切模型、不使用 API Key、额外额度或付费回退。

最终在不可取消的清理段撤销并删除自己创建的两条记录，关闭监听器，等待请求终结并释放并发。报告逐步记录专用记录 ID，保存为权限 0600 的 `reports/database-verification.json`；通过状态为 `REAL_DATABASE_ACP_ACCEPTANCE_PASSED`。如果进程异常退出或留下运行中报告，先按报告中的精确 ID 核对撤销和清理，不重复执行整套实号验收，更不能按名称前缀批量删除其他记录。

自动数据库回归使用单独创建并迁移的测试 PostgreSQL 数据库及模拟 ACP，覆盖成功、额度耗尽和模型不符；核对每种场景均只启动一次 agent、没有模型切换或重试、专用记录被清理，其他调用方记录保持不变。测试中创建或迁移测试库不代表生产验收会运行迁移。

### 官方账号状态、订阅档位与到期刷新证据

先构建包含 `account-status` 模式的新执行包；构建不部署、不启动常规网关，也不访问实号模型：

```bash
mvn --batch-mode --no-transfer-progress -pl prism-app -am -DskipTests package

python3 scripts/antigravity_acp_account_checks.py \
  --state "$HOME/.local/state/prismllm/antigravity-acp-pilot" \
  --model gemini-4-hydrogen
```

检查器分两步执行，始终持有专用 home 的同一个排他锁：

1. 在原隔离环境执行官方 `agy --log-file /dev/null --output-format json --print /usage`，先要求退出码正常、`num_turns == 0`、所有报告的生成用量为整数零，且未出现额度耗尽或服务停用。任何配额行明确显示剩余 `0%` 时保守停止，不尝试判断是否可以换到另一个模型，也不启动第二步。
2. 通过仅支持只读账号检查的 Kotlin `account-status` 模式启动官方 ACP，发送 `initialize` 和 `session/new`，不发送 `session/prompt`。只从该会话的 `_meta.antigravity.tier.name` 提取严格匹配的档位，确认当前模型等于已验收模型；不发送模型选择 RPC。Java 和 agent 都不继承 API Key、数据库配置或其他父进程环境变量。

`--model` 仅核对本地守卫和 ACP 当前模型，不会作为 CLI 模型选择参数传入。第二步失败、模型不符或额度不足立即停止，不重试、不回退；子进程超时只终止本轮独立进程组。官方状态与 ACP 档位明确矛盾时记为 `OFFICIAL_SUBSCRIPTION_EVIDENCE_CONFLICT`，禁止继续。

报告为 `reports/account-verification.json`，权限 0600；新检查开始就使旧成功状态失效，检查中断或失败都不能继续凭旧成功记录放行。仅保存规范化档位、固定模型和证据来源，不保存原始 ACP 帧、账号邮箱、升级链接或凭据。最新账号报告与原功能报告用途不同，不覆盖 `verification.json`。

三种证据必须分开记录：

- **功能及登录复用**：ACP 或 HTTP 生成成功、进程重启后无需重新登录。只能说明当前账号能够使用该模型，不证明付费订阅档位或令牌刷新。
- **官方订阅权益**：接受当前隔离 CLI 的官方 ACP `session/new._meta.antigravity.tier.name`、官方只读状态中明确的档位字段，或官方订阅页面与当前 CLI 账号的同一性核对。当前会话元数据不需要借用浏览器身份。初始化中的能力声明、tier ID、模型名和升级链接都不能单独证明付费档位；Free、未知或畸形档位不标为付费订阅。浏览器里的 Google One 档位本身不能证明是当前 CLI 账号；不从模型回答或剩余额度反推 Pro/Ultra。读取状态产生了生成轮次时，即使文本中有档位也不作为官方只读证据。账号持有人的档位确认单独记录为人工确认，不覆盖官方核验标志。
- **自然到期后的刷新**：需要能归属于当前 CLI 登录的刷新前到期元数据，以及自然到期后官方刷新和登录继续可用的证据。仅访问 OAuth 令牌端点、重启进程成功或模拟过期测试都不充分。普通账号检查器只记录 CONNECT 成功的允许域名，不解密 TLS，不读取凭据文件或 token 端点正文，不修改 token、到期时间或系统时钟。下述独立到期验收入口在另获明确授权后，仅提取本地到期时间。

HTTP 回归中的 `http-refresh` 由模拟 CLI 在第二次启动时写入合成的刷新标记，用于防止把模拟续期误报为实号续期；其 `refreshAfterExpiryVerified` 必须仍为 `false`。

当前固定的 CLI 1.5.17 已观测到会话档位元数据，但其官方只读状态和 ACP 会话没有公开刷新前到期时间。因此普通账号报告保留 `naturalExpiryObservationStarted: false`、`naturalExpiryObservationBlockedBy: OFFICIAL_EXPIRY_METADATA_UNAVAILABLE`，不凭猜测的有效期启动盲等或反复探测。2026-11-26 账号持有人另行明确授权：允许只读打开隔离 CLI 本地凭据，仅提取到期时间；不得展示或导出 token、修改凭据或时钟。此授权不扩大为读取其他账号、生产凭据或令牌端点正文。

### 授权后的自然到期验收

```bash
python3 scripts/antigravity_acp_natural_refresh.py \
  --state "$HOME/.local/state/prismllm/antigravity-acp-pilot" \
  --model gemini-4-hydrogen \
  --allow-read-expiry \
  --max-wait-seconds 4500
```

需要已构建的 `account-status` 执行包，以及同一隔离 home 的成功功能守卫和官方 Pro 账号报告。没有 `--allow-read-expiry` 不打开凭据；不提供任意凭据路径参数。该开关必须以账号持有人的授权为前提。

1. 全程持有原 `pilot.lock`，使用只读文件描述符和逐级 `O_NOFOLLOW` 打开隔离 CLI 的 `antigravity-oauth-token`，只取当前版本的 `token.expiry`。目录必须为当前用户的 0700 目录，凭据必须为 0600 普通单链接文件；拒绝符号链接、硬链接、过大文件、重复 JSON 字段、无时区或未知格式。不返回或持久化任何 token、邮箱、账号标识、凭据内容或其哈希。
2. 记录刷新前到期时间，在有限等待预算内自然等待到该时间后两秒。等待期间不运行 CLI、不访问网络、不反复读取凭据；同时对照单调时钟，发现时钟跳变就停止。到期时重新读取到期字段，若等待期间已被其他操作刷新则停止，不能将其冒充本次刷新；已经过期才开始观测、超过等待预算或缺少元数据也不发起请求。
3. 再次核对原守卫、账号报告、额度策略及设置未变，随后只执行一次官方 `/usage`。额度耗尽、服务停用、鉴权失败或非零生成用量均立即停止，不再启动 ACP 会话。状态安全时才执行一次官方 ACP `initialize` / `session/new`，核对原固定模型和官方 Pro 档位，不发送 `session/prompt` 或模型选择 RPC。
4. 仅当自然到期已发生、本轮观察到 OAuth 端点连接、本地到期时间由官方 CLI 更新至未来、到期后官方 ACP 登录与 Pro 核验成功且全程零生成时，才标为 `NATURAL_EXPIRY_REFRESH_VERIFIED`。单个连接、合成刷新字段、只推进到期值或重启成功都不能通过。检查器自身始终不写凭据；正常刷新时只有官方 CLI 管理其凭据更新，不人为修改 token、到期字段或系统时钟。

结果独立保存到 `reports/natural-expiry-verification.json`（0600）；`WAITING_FOR_NATURAL_EXPIRY` 和任何失败状态的 `refreshAfterExpiryVerified` 都为 `false`。新一轮开始会使该报告的旧成功状态失效，但不覆盖原功能、HTTP、数据库或账号档位报告。保存的敏感范围标记为 `credentialReadScope: EXPIRY_ONLY`，只有脱敏的刷新前后到期时间、只读状态、规范化档位和隔离结果。原账号报告仍只说明它自己那轮检查的证据，不因新增报告而改写历史状态。

参考：[官方 CLI 命令参考](https://antigravity.google/docs/cli/reference/)。使用官方 CLI 不意味着第三方网关获官方认可，也不能保证账号不会受限。

## 2026-11-26 13:24 生产数据库验收及当时证据状态

- 13:24（Asia/Shanghai）在已确认的生产 PostgreSQL 上完成隔离验收，状态为 `REAL_DATABASE_ACP_ACCEPTANCE_PASSED`；真实 HTTP 路由复用生产的数据库密钥查找与鉴权逻辑，十三项鉴权/授权检查通过。
- 只发出一次 `gemini-4-hydrogen` 短文本生成请求并成功，零重试、零模型切换、零回退；API Key 环境未传入，禁用额外额度的策略未变。不重复此前已经完成的三次流式、非流式与取消实号验收。
- 两把临时密钥均撤销并删除，另行只读查询确认其记录数为零；监听器停止、请求终结、并发释放。额外的脱敏隔离回执为 `reports/database-isolation-verification.json`，记录生产网关实例及启动时间未变、原始 ACP 成功报告和额度策略哈希未变；未部署或重启生产网关。
- 官方 `/usage` 只读检查成功，生成轮次和报告用量均为零，观察到一次 OAuth 令牌端点连接；官方状态没有提供明确档位、账号关联标识或刷新前到期时间。因此 `subscriptionEntitlementVerified` 和 `refreshAfterExpiryVerified` 仍为 `false`。
- 另行只读查看的官方 Google One 页面显示 `Google AI Pro (5 TB)`。2026-11-26 账号持有人明确确认：该浏览器账号与当前 CLI 账号不是同一个账号，但当前 CLI 账号也是 Pro。CLI 档位按“账号持有人确认：Pro”记录，不能借用另一个浏览器账号的页面作为官方证据；官方 `subscriptionEntitlementVerified` 仍为 `false`，到期刷新状态不变。不记录账号邮箱或凭据。
- 本次定向构建、107 项 Kotlin 测试及 38 项 Python 隔离策略与账号证据测试通过；自动测试不消耗实号额度。这是定向回归结果，不代表所有仓库测试均已运行。

## 2026-11-26 13:42 当前 CLI 官方 Pro 档位核验

- 官方 Linux `agy` 1.5.17 的新 ACP 会话直接返回档位元数据；当前 CLI 的 `subscriptionTier: PRO`、`subscriptionEntitlementVerified: true`、`subscriptionEvidence: OFFICIAL_ACP_SESSION_TIER_METADATA`。这补齐了此前只有账号持有人确认的档位证据，与已明确为另一账号的浏览器页面无关。
- 本轮只读 `/usage` 与 ACP 当前模型检查通过，固定为 `gemini-4-hydrogen`；零生成轮次、零报告的生成用量，没有调用 `session/prompt`，没有模型选择或付费回退。本轮观察到的 OAuth 令牌端点连接数为零，不能沿用早前那次连接来宣称本轮刷新成功。
- `refreshAfterExpiryVerified: false`，原因是缺少官方公开的刷新前到期元数据；未读取或改写凭据、修改系统时间、重新登录或开启盲等循环，未将模拟刷新写为实号刷新通过。
- 脱敏隔离回执为 `reports/account-isolation-verification.json`（0600）：生产网关实例及启动时间未变、原功能守卫和禁用额外额度的策略及设置未变，本轮未访问数据库，不修改线上路由或重新部署。只读元数据发现记录为 `reports/current-account-metadata-discovery.json`，仅保留字段路径，不保留原始帧或账号信息。
- 定向构建及 115 项 Kotlin、48 项 Python 回归通过，覆盖 Pro/Free/缺失或畸形元数据、初始化能力或模型名称冒充档位、档位冲突、固定模型不符、配额剩余为零、额度不足不再启动会话、超时进程组清理，以及不能从合成字段或 OAuth 连接误报自然到期刷新。没有运行全仓库测试，也未重复实号生成验收。

## 2026-11-26 14:16 自然到期刷新实号验收

- 账号持有人已明确授权只读隔离 CLI 本地凭据，仅提取到期时间。当前 CLI 1.5.17 的到期字段为 `token.expiry`；14:07:36（Asia/Shanghai）取得的到期时间为 2026-11-26 14:16:26.582573，随后自然等待 532.145 秒。等待期间未启动 CLI、未发请求、未改时钟、未改写凭据或制造过期。
- 14:16:29 才启动官方只读检查；本轮观察到一次 OAuth 端点 CONNECT，官方 CLI 随后持久化的新到期时间为 15:16:29.052574。14:16:37 完成的官方 ACP 会话继续可用，当前模型仍是 `gemini-4-hydrogen`，官方档位仍为 `PRO`；两阶段检查没有生成请求，官方报告的生成轮次和用量均为零。判定基于“自然到期、到期字段推进、OAuth 连接、到期后真实登录继续可用”的联合证据，不把单次连接当作充分证据。
- 独立结果 `reports/natural-expiry-verification.json` 为 `NATURAL_EXPIRY_REFRESH_VERIFIED`，`refreshAfterExpiryVerified: true`、`subscriptionEntitlementVerified: true`；没有保存、输出或导出 token、账号身份、原始帧或令牌端点正文。仅官方 CLI 在正常刷新中更新自己的凭据，验收代码没有写凭据。
- `reports/natural-expiry-isolation-verification.json` 为 `NATURAL_REFRESH_ISOLATION_VERIFIED`；原功能守卫、额度策略、CLI 设置及此前账号报告均未变，生产网关实例及启动时间未变。本轮未访问数据库，没有传入 API Key 环境、启用额外额度、切模型、付费回退、部署或改线上路由。
- 本轮重新执行定向构建及 115 项 Kotlin 测试通过，Python 回归扩展至 70 项通过。新增 22 项测试覆盖真实 `token.expiry` 结构、明确授权、只读打开、权限/链接/命名管道/重复 JSON 防护、自然等待、时钟跳变、等待时凭据或策略变化、额度耗尽立即停止、假刷新字段、取消和锁释放。全部自动测试只使用临时假凭据和模拟 CLI，不消耗实号额度；没有运行全仓库测试，也没有重复此前的生成验收。

## 2026-11-26 14:45 完整应用隔离端到端回归

- 新增 `AntigravityAcpApplicationTest` 的 15 项用例。真实临时 PostgreSQL、实际 `GatewayApplication.install`、Netty 回环 HTTP 和生产配置的子进程命令联动；子进程替换为模拟 ACP，不启动官方 CLI，不访问生产数据库或真实凭据。
- 覆盖默认关闭时 ACP 路由不存在、Bearer 数据库鉴权与白名单、固定模型目录、CORS 和严格载荷、非流式和完整 SSE、思考中或部分输出后的真实 TCP 断线取消、在途密钥轮换、后续密钥撤销、专用登录标记复用、停机 drain 与拒绝新请求。取消均检查 agent 确认，不以 socket 关闭或杀进程代替。
- 同一个测试应用预先配置与 ACP 同名的普通模型路由；额度不足、服务停用、初始或运行中模型不符均停止，模拟普通上游没有收到回退请求，之后显式调用普通路由仍可成功。每项检查均保留普通路由快照与无关密钥记录，不因试点修改它们；结束后并发占用为空、自有监听器关闭。
- 14:45:22（Asia/Shanghai）定向 Maven `package` 成功：应用模块 127 项、quota 模块 3 项，共 130 项 Kotlin 测试通过；Python 70 项回归通过。日志为本机 `/tmp/prism-acp-pilot-regression-package.log` 和 `/tmp/prism-acp-pilot-python-regression.log`。未运行全仓库测试，也未改动退役工作的分类基线。
- 本轮仅新增测试和更新本文档，没有业务代码变更、实号请求、真实凭据读取、生产库访问、线上路由变更或部署；不消耗订阅生成额度，不切模型、不自动重试、不付费回退。此前九份功能、数据库、账号、自然到期及隔离报告与基线逐一校验保持不变，不把本轮模拟结果写入实号守卫。

## 2026-11-26 独立 PR 分支回归

- 从主分支 `7b4f88d2` 建立独立 ACP 分支，只提交默认关闭的 ACP 接入、隔离验收、回归测试及本文档；不包含源实验工作区的旧直连退役、Gemini API 替代或管理台迁移改动。源实验工作区保持原样，现有线上路由不变。
- 在该分支重新执行定向离线 Maven `package`：应用模块 135 项、quota 模块 3 项、test-support 模块 3 项，共 **141 项 Kotlin 测试通过**，无失败或跳过。包含 15 项完整应用端到端回归、原有 `CodeAssistIntegrationTest` 及两个模块中的 `TestClassificationTest`；这补齐了提交分支的分类与原有路径兼容性证明，不等于运行全仓测试。
- **70 项 Python 回归通过**，并在与新增 CI 相同的空环境变量、临时 home 条件下再次通过；新增工作流经 `actionlint` 1.7.7 检查通过。自动测试仅使用临时数据与模拟 ACP，不读取真实凭据、访问生产库或消耗订阅额度。
- 本次提交准备不重复历史实号验收，不修改现有私有验收报告，不部署、重启服务、启用试点或改线上路由；单账号、单并发、受控文本和固定模型边界不变。剩余部署实号组合验证及故障恢复演练仍按前文单独推进。

## 2026-11-26 PR #278 第一轮审查与 CI 回归

- 专用密钥白名单在严格 UUID 校验后统一为小写并去重，鉴权边界按同一规范比较；配置仍为不可变快照。新增大写白名单对应小写、大写、混合大小写数据库 ID 的鉴权回归，同时验证无关、畸形 ID 和受限访问策略不能因此获权。
- 两条官方只读账号观察路径均创建自有进程组；超时、取消和其他等待异常都先终止、必要时强制终止并回收所属进程，再释放隔离锁。新增真实 SIGTERM 的合成子进程回归，确认清理发生时锁仍被持有、子进程已回收，失败证据不能误报可继续；不启动官方 CLI。
- CLI 安装先在私有临时目录完成固定发行包校验、复制、权限和摘要，再以不覆盖已有路径的方式发布。只有与重新验证的固定发行包一致时，才恢复二进制或收据单边存在的中断状态；不认识的残留文件、摘要不符、符号链接和特殊路径均原样保留并停止。新增复制失败、发布中断、恢复和并发占位的离线回归。
- 初始 CI 后端因 JVM 堆耗尽而失败；本地在 1536 MiB 测试堆上复现。堆诊断显示 JVM GC 指标通知监听器持有已关闭应用的指标注册表，进而保留应用与指纹检测数据。现在由网关指标对象统一拥有并关闭可关闭的指标 binder 及注册表，插件未使用的默认注册表也立即关闭；不提高堆上限或删减测试。新增重复关闭、单个 binder 关闭失败仍继续清理，以及完整应用关停后注册表已关闭的回归。
- 定向离线 Maven `package` 通过：应用模块 142 项、quota 模块 3 项、test-support 模块 3 项，共 **148 项 Kotlin 测试通过**；空环境变量、临时 home 下 **88 项 Python 回归通过**。本轮不访问生产数据库、真实凭据或订阅接口，不消耗订阅额度，不切模型、不付费回退，不修改线上路由；原实验工作区和九份私有验收报告保持不变。
- 16:05:28（Asia/Shanghai）完整后端 reactor 生命周期 `mvn --offline --batch-mode --no-transfer-progress -pl prism-app -am -DargLine=-Xmx1536m test` 成功：应用模块 **1,008 项**、上游模块 **654 项**，合计 **1,662 项测试通过**，无失败、错误或跳过。包含先前 CI 堆耗尽时正在运行的 `SubscriptionOAuthIntegrationTest`，不通过扩堆、拆分进程或跳过测试规避问题。日志为本机 `/tmp/prism-acp-278-round1-metrics-lifecycle.log`；不等同于扩展插件或前端测试。工作流 `actionlint` 1.7.7 与 `git diff --check` 通过，远端 CI 与新 HEAD 全量复审仍以实际结果为准。
- 第一轮提交 `4a00ba94` 的远端 CI `45780673507` 已成功；CI 使用 PR 合并提交 `aa4b54c6`，应用模块 **1,008 项通过**，无失败、错误或跳过，隔离策略与工作流检查同样通过。PR-Agent 在该 HEAD 的全量完整复审确认三项初始意见均已解决，同时指出自定义模型配置项通知和 `verify/probe` 取消链路仍需补齐，不能将此状态报告为可合并。

## 第二轮收敛校验约束

- 固定模型的事实由会话建立时确认的模型及实际配置项 ID 共同决定；当前会话任何模型漂移都必须在转发后续文本之前停止，不切模型、不重试、不回退。外来会话不能改变本会话，重复同模型和无关配置更新不应误停。
- 隔离锁覆盖它拥有的整个子进程生命周期，不只覆盖入口 Python：正常完成、非零退出、等待异常、超时及 SIGTERM/键盘取消，都必须在所属进程终止并回收后才能解锁。准备、验收、登录和账号观察路径使用同一清理责任，不另加锁、重试或持久化状态。

| 路径与事件 | 成功/重复 | 失败/模型漂移 | 超时/取消 | 不允许的副作用 |
| --- | --- | --- | --- | --- |
| 当前会话模型更新 | 仅保持确认的固定模型 | 先停止再处理后续输出 | 取消期间不转发输出 | 不选模型、不自动重试、不付费回退 |
| 账号观察及 `verify/probe` | 回收后记录结果并解锁 | 回收后失败，不复用成功证据 | 终止所属进程链并回收后解锁 | 不清理外来进程，不与新登录并发 |
| 登录/agent 与准备子进程 | 保留现有策略及登录复用 | 清理后恢复策略 | 保持沙盒父子终止关系 | 不额外读取或导出凭据，不改线上配置或时钟 |

## 2026-11-26 PR #278 第二轮回归

- 模型通知校验改为接收所属会话，而不是只接收会话 ID；固定模型模式同时识别会话实际的模型配置项 ID、兼容的 `model` ID、模型类别及旧版 `current_model_update`。自定义 ID 更新省略类别、缺失或畸形当前值时停止；当前会话畸形配置通知按协议错误停止。外来会话、无关选项、同值重复更新和取消中通知不误转发或重新激活生成。
- 新增 **24 项**模型通知用例；其中 **13 项**在修复前失败，修复后 `AntigravityAcpPilotTest` **57 项通过**。覆盖漂移前文本仍可见、漂移后文本被丢弃、取消得到确认、子进程退出和不发送模型选择 RPC；不以模拟结果替代实号证据。
- 观察器与主试点复用同一套所属进程清理。验收、准备、agent/login 以及沙盒内 CLI 的子进程都采用独立所属组；SIGTERM 转为可清理的中断，结束后恢复原处理器。组长退出后仍检查并等待本组包装进程完成清理，必要时只强制结束所属组；清理期间屏蔽重复终止信号，先终止回收再解锁。成功、非零退出、超时和等待异常均覆盖该责任，不新增锁、重试或凭据状态。
- 新增合成进程测试，实际对 `verify`、`probe` 和 `prepare` 入口发送 SIGTERM；模拟 Java 先退出、Python agent 延迟清理和独立沙盒进程，检查锁仍持有、相关进程已停止、信号处理器已恢复及旧证据已失效。进程收养仅存在于测试控制器，不修改生产进程管理。空环境、临时 HOME 的 **92 项 Python 回归通过**；组合离线 `package` 的 **172 项 Kotlin 回归通过**（app 166、quota 3、test-support 3）。
- 16:52:16（Asia/Shanghai）再次运行相同 1536 MiB 堆上限的完整离线后端 reactor：应用模块 **1,032 项**、上游模块 **654 项**，共 **1,686 项测试通过**，无失败、错误或跳过；日志 `/tmp/prism-acp-278-round2-full.log`。本轮仍未访问生产数据库、真实 CLI 凭据或 Google 订阅接口，未部署、改线上路由或消耗订阅额度；原实验工作区与九份私有报告校验不变。远端 CI 与复审需在第二轮提交后独立确认。
