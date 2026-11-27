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

## 服务端配置

要求与 ACP 试点一致的 Linux x86_64 环境、Python 3.11+、Bubblewrap 和可用的本机 HTTP 代理。
登录期间由脚本校验并安装固定官方 CLI，再验证既有文件/进程/网络沙盒；不继承宿主 API Key、
Google 凭据、钥匙串或 D-Bus。每个 CLI 档案使用独立私有 home、工作目录和排他锁。
Google token 只保留在 CLI 自己管理的私有 home，设置始终恢复为 `useG1Credits: false`。

```dotenv
ANTIGRAVITY_AGY_ENABLED=true
ANTIGRAVITY_AGY_SCRIPT=/absolute/path/to/PrismLLM/scripts/antigravity_agy_admin.py
ANTIGRAVITY_AGY_STATE_DIR=/absolute/path/to/private/antigravity-agy-accounts
ANTIGRAVITY_AGY_PROXY=http://127.0.0.1:7897
```

默认关闭。未配置时管理台明确报错，不自动回退旧 OAuth。上述路径由部署者设置，不允许浏览器传入。
提供商列表的 Antigravity 配置状态取决于此 agy 登录服务，不再沿用旧 OAuth 客户端配置的可用状态。
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
