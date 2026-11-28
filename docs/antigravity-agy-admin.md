# 管理台通过官方 agy 登录 Antigravity

## 交付范围

Antigravity 的管理台新增和旧账号重新登录不再调用 `/admin/api/oauth-login/start`。
该旧入口对 `provider=antigravity` 返回 `antigravity_agy_required`；ChatGPT OAuth 不变。
管理台启动官方 `agy acp --login`，仅展示 CLI 输出且经过严格校验的 Google 授权链接，
将用户提交的一次性验证码写入该 CLI 的终端。不自行生成 OAuth 参数、交换授权码、导入旧 token 或读取 CLI 凭据。

授权完成后创建一次只读 ACP 会话，读取 CLI 返回的当前模型和官方档位；不调用 `session/prompt`、
不切模型、不读取凭据或调用付费 API。档位未知时明确显示“未确认”，不把登录成功等同于 Pro 权益通过。
使用官方 CLI 不能保证账号不受 Google 限制，服务停用仍需在官方渠道确认和处理。

官方 agy 档案现可作为普通订阅凭据参与已配置的模型路由。新账号通过登录后登记到 Antigravity 订阅渠道，
但不会自动插入任何模型路由；管理员需在“模型凭据路由”中明确选择账号。旧 OAuth 迁移保留账号 ID、
渠道关联和已有路由身份，不会自动改写路由顺序或模型映射，并清除该记录中的旧 OAuth token。
迁移不代表所有旧模型都能由当前 CLI 档案提供；仅经官方 CLI 确认、且与共享模型映射兼容的模型可路由。
官方 CLI 数据面只调用 CLI 当前模型和订阅自带额度；ACP 明确报告额度不足时返回不可重试错误，不切模型、
不转付费 API，也不继续尝试路由中的后续静态凭据。该集成默认关闭，本次不部署、不改线上路由。

## 管理台开关与按需准备

无需填写 agy 环境变量。在“系统配置 → Antigravity 运行环境”或 Antigravity 账号区的“配置运行环境”
打开开关，确认服务环境内可访问的本机 HTTP 代理并保存。设置持久化到数据库，后台异步准备，
HTTP 请求不等待安装结束。默认关闭时不检查、不下载、不安装相关运行依赖。

1. 检查当前服务环境是否为 Linux x86_64，以及 Python 3.11+ 和 Bubblewrap 是否已存在。
2. 管理台列出缺失依赖，仅在缺失时通过当前系统的 apt-get 软件源下载安装；已有依赖不重装。
   不接受浏览器传入命令、脚本路径、任意下载链接或额外软件源，不运行 sudo、不自动提权。
   不支持 apt-get、没有安装权限、根文件系统只读或源中的 Python 版本不足时明确提示，由部署管理员处理后重试。
3. 下载并校验仓库固定版本官方 agy，在新的私有运行目录检查文件、进程和网络沙盒。
   已有 CLI 必须通过固定版本完整性校验才可复用；新账号仅复制已校验的 CLI 和校验记录，不复用运行目录中的 home。
4. 只有检查全部通过才显示“运行环境已就绪”，放行官方登录和已配置的 agy 路由。
   准备不会启动 Google 授权、生成请求、读取旧 token 或自动添加模型路由。失败时显示原因，可显式重试。

镜像不预装 Python 和 Bubblewrap，只携带随应用发布的可信脚本及固定版本清单。
首次开启时下载的系统依赖位于服务所在容器/机器，而不是当前浏览器电脑。
默认镜像基于 Ubuntu 24.04，使系统源中的 Python 满足最低版本；其他部署环境需自行保证兼容的软件源、
安装权限和可写文件系统。容器必须允许 Bubblewrap 所需的内核命名空间；这不是下载软件能修复的问题，
不会通过关闭沙盒、提权容器或旧 OAuth 回退绕过检查。

准备状态包括检查、安装缺失依赖、下载校验 CLI/沙盒检查、就绪、失败和关闭。
关闭后不删除依赖或账号凭据；已开始的系统安装步骤不会因管理台关闭开关而中断，完成该步骤后停止后续准备。
停止期间不能重新开启，需等待状态变为关闭后再开启，并重新完成全部准备检查后才会放行服务。
安装期间不要重启服务或更新容器。重建容器后，仍开启的设置会再次检查并仅安装缺失依赖。

默认脚本位于应用工作目录的 scripts/antigravity_agy_admin.py，状态根目录为 data/antigravity-agy；
Docker 中分别是 /app/scripts 和 /app/data/antigravity-agy，Compose 使用独立的 prism-llm_agydata 命名卷持久化。
HTTP 代理仍要求 http://127.0.0.1:端口，不能携带账号密码；容器回环地址指容器自身，需要部署者提供可用出口。
不继承宿主 API Key、Google 凭据、钥匙串或 D-Bus。每个 CLI 档案使用独立私有 home、工作目录和排他锁。
Google token 只保留在 CLI 自己管理的私有 home，设置始终恢复为 useG1Credits: false。

开关和代理只从数据库中的管理台设置读取，不再读取旧的 agy 部署环境变量；未保存开启设置时保持关闭。
旧部署中残留的相关环境变量可删除，不会再影响运行环境。浏览器不能修改脚本、Python 或凭据目录路径。
提供商列表的 Antigravity 配置状态取决于已就绪的 agy 登录服务，不再沿用旧 OAuth 客户端配置的可用状态。
状态根目录必须属于服务运行用户，权限 0700，不得通过符号链接引用；持久化目录需要备份和持续保留。
不能把 #278 已验收的试点状态目录误设为新的档案根目录。原有试点登录保持原样，不扫描、读取或搬运
该目录中的凭据；旧 OAuth 数据库账号迁移仅通过管理台选中旧记录后，用官方 CLI 重新授权完成。

## 新增和旧账号重新登录

1. 在管理台打开 Antigravity 提供商，选择新增账号，点击“启动官方 agy 登录”。
2. 打开官方登录链接，在 Google/Antigravity 官方页面完成授权。
3. 将页面显示的一次性验证码填入管理台“官方页面验证码”，点击“提交给官方 CLI”。
   不接受完整回调 URL、换行或终端控制字符。验证码不写审计明细、账号档案或浏览器持久存储。
4. 等待 CLI 完成登录与只读会话检查。管理台展示当前模型、官方档位或“未确认”；只有确认 Pro 档位的账号
   才登记为 Antigravity 订阅凭据。新账号仍需由管理员显式加入模型路由。
5. 迁移旧账号时，在旧 Antigravity OAuth 记录的操作菜单选择“使用 agy 登录迁移”，按同样流程授权。
   必须确认浏览器中登录的是预期 Google 账号：系统不读取 CLI token 来核对邮箱，也不自动判定两边身份相同。
6. 成功迁移后，原账号记录 ID、渠道关联、启停状态、优先级和已保存模型路由身份保持不变；数据库中的旧
   access/refresh token 密文清空，授权方式改为 AGY，已验证的官方档位与当前模型写入目录。迁移不是 OAuth
   token 导入，不能撤销 Google 已签发的凭据，也不会自动更改已有路由或共享模型映射。

## 普通数据面路由

1. 在“Antigravity 官方 agy 授权”中确认档案已登记、档位为 PRO，并查看其可路由模型。
2. 在“模型凭据路由”中编辑一个模型，将 Antigravity 渠道下的 AGY 账号放入所需位置。可按需保留其他候选，
   但额度耗尽、服务禁用等 AGY 错误不会向后切换候选；请不要把付费或不同订阅路径当作回退。
3. 确认所选模型已在共享映射中启用且与档案当前模型相符。若旧路由引用的模型不在该档案已确认目录内，
   路由记录会被保留，但该账号不会成为该模型的可用候选；管理员需自行配置有效模型映射与路由。
4. 通过普通的 /v1/chat/completions 调用方接口发送请求。流式与非流式都走 ACP；客户端断开会请求
   ACP 取消当前 turn。同一个 AGY profile 可供后续请求重新启动和复用，不会重新登录或切换模型。

关闭弹窗、切换提供方或点击取消会终止本次所属 CLI；登录最长十分钟，不自动重试。
取消或登录校验失败不会创建成功档案，但 CLI 可能已经保存了登录中途产生的自身状态；不会据此放行路由。
若 CLI 校验已成功、后续数据库登记失败，已持久化的档案仍会占用名额；没有账号或路由引用时可显式清理，不能据此放行路由。
重新登录已有旧账号关联的 CLI 档案时复用同一独立 home，由 CLI 管理续期；不要主动清除凭据。

孤儿档案可在管理台逐项清理。只有不再被任何订阅账号、迁移来源、已保存模型路由或进行中的登录引用的档案才会显示清理按钮；确认后会删除其独立 agy home 与档案记录，释放一个档案名额。清理不可撤销，后续使用需要重新授权。该操作只删除本机 agy 凭据和档案元数据，不会撤销 Google 已签发的授权。系统不会自动清理档案，也不会清理仍关联或正在使用的账号凭据。

## 管理 API

所有端点要求管理会话 Cookie；写操作还要求有效 `X-CSRF-Token`。登录状态属于发起管理员，其他管理员
不能查询或提交其验证码。响应均不含 Google token，登录响应加 `Cache-Control: no-store`。

| 方法 | 路径（前缀 `/admin/api`） | 用途 |
| --- | --- | --- |
| GET | /antigravity-agy-settings | 开关、服务端准备状态、缺失依赖与失败原因 |
| PUT | /antigravity-agy-settings | 保存开关和本机 HTTP 代理，开启后异步检查并按需安装 |
| GET | `/antigravity/agy/accounts` | 配置可用性与 CLI 档案元数据 |
| POST | `/antigravity/agy/logins` | 发起登录，202；可带 `name`、`sourceAccountId` |
| GET | `/antigravity/agy/logins/{loginId}` | 轮询脱敏状态和官方登录链接 |
| POST | `/antigravity/agy/logins/{loginId}/code` | `{ "code": "一次性验证码" }`，202 |
| DELETE | `/antigravity/agy/logins/{loginId}` | 取消本次登录，204 |
| DELETE | `/antigravity/agy/accounts/{profileId}` | 确认后删除孤儿档案与本地 agy home；成功 204，仍被引用或登录占用时 409 |

状态为 `PREPARING`、`AWAITING_CODE`、`VERIFYING`、`REGISTERING`、`AUTHENTICATED`、`FAILED`、`CANCELLED`、`EXPIRED`。CLI 验证成功后先进入 `REGISTERING`，仅在订阅路由账号登记成功后进入 `AUTHENTICATED`；此阶段不可取消。档案列表和状态查询只读，不会创建、恢复或重置数据库账号。
发起和提交验证码不是可无条件重试的操作：全局只允许一个在途登录，第二次发起返回 409；
验证码只在 `AWAITING_CODE` 接受一次。网络失败先查询当前状态，不自动重发验证码。
服务重启后在途登录不会恢复，已成功档案的脱敏元数据和 CLI 登录 home 持久保留。

`accounts.json` 只保存 UUID、名称、人工关联的旧账号 ID、官方档位、当前模型、授权时间和
固定为 false 的 `routingEnabled`。不保存授权 URL、验证码、邮箱、原始 CLI 输出或 token。
CLI 原始标准错误丢弃；审计只记录操作类型和结果，不记录请求正文。

## 自动回归

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_antigravity_acp_*.py'
mvn --batch-mode --no-transfer-progress -pl prism-app -am \
  -Dtest=AntigravityAgyLoginServiceTest,AntigravityAgyConfigTest,AntigravityAcpApplicationTest,ProviderModelAvailabilityTest,SubscriptionOAuthIntegrationTest,ModelCredentialRoutesIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
cd web && npm test && npm run build
```

自动回归只使用临时目录、模拟 CLI/ACP、合成验证码和测试数据库，不读取真实凭据、不访问 Google，
不消耗订阅额度。真实管理台登录仍需持有人单独授权验收；授权本身不包含生成测试或上线许可。
