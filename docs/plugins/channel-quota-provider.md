# 渠道共享额度插件

额度查询是可选的管理功能。网关核心提供 SPI、加载器、渠道设置、手动查询 API 和通用弹窗；上游差异由独立插件实现。关闭功能或不安装插件时，不会向上游发起额度请求。

每次查询只返回实际使用密钥对应的上游账户余额或订阅额度池，不跨密钥累加。同一渠道可能混用自有密钥、福利密钥等不同账户凭据，不能据此认定所有密钥共用余额。结果会显示实际查询密钥的名称和 ID，认证失败换密钥时显示查询顺序，便于判断额度属于哪个账户。

## 安装官方插件

需要 JDK 21 与 Maven。在仓库根目录构建核心的 SPI 制品，再构建独立插件：

~~~bash
mvn -DskipTests install
mvn -f extensions/quota-providers/pom.xml package
mkdir -p plugins
cp extensions/quota-providers/target/llm-gateway-quota-providers-0.1.0-SNAPSHOT.jar plugins/
~~~

官方包同时提供两个可选的 Provider：

| ID | 上游 | 共享额度来源 |
| --- | --- | --- |
| sub2api | Sub2API | /v1/usage 的账户 balance，或已确认的 subscription 剩余额度 |
| new-api | New API | 系统访问令牌查询 /api/user/self 的 data.quota；或较新版本 /api/usage/token/ 的 data.user_quota；由 /api/status 确定单位 |

插件目录由 QUOTA_PLUGIN_DIR 指定，默认是网关工作目录下的 plugins。Compose 将宿主机 ./plugins 只读挂载到 /app/plugins。若改目录，要同步修改容器环境变量和卷挂载目标。

插件只在启动时加载。已有当前版本网关镜像时，复制 JAR 后执行 docker compose restart gateway；首次使用这些代码则执行 docker compose up -d --build gateway。本地运行 JAR 时重新启动网关进程即可。删除插件 JAR 并重启可卸载；渠道设置保留，查询时会提示插件未加载。

官方插件不会默认打包进核心 JAR 或 Docker 镜像。仓库 CI 分别测试核心和官方插件。目录按文件名排序发现，同 ID 只保留首个合法实现；非法 ID、不支持的 SPI 版本、重复 ID、损坏的服务声明会记录启动日志，健康插件仍可加载。

## 管理界面

在「渠道」列表点击「额度」，选择插件并保存，再点击「查询额度」。结果中的「查询密钥」是最后实际调用插件的密钥，命中缓存时保留当时的来源；若发生鉴权回退，还会显示「查询顺序」。打开弹窗和保存设置均不会自动查询；选择「不启用」并保存即可关闭。

同一渠道可以保存不同账户的密钥。在弹窗的「查询对象」中选择一个已保存的密钥，分别配置插件、New API 用户 ID、系统访问令牌及可选代理，再保存并查询。每个密钥的设置与独立查询令牌分别存储，不继承渠道的系统令牌或其他密钥的账户。尚未配置的密钥默认关闭；选择「不启用」只关闭当前目标。切换对象前需保存或放弃修改，避免将未保存的账户信息带入另一密钥。

指定密钥的查询仅使用其自身模型凭据或为其保存的独立查询令牌，失败时不换用其他密钥；即使密钥处于停用状态，也可以手动查看该账户额度，不改变启停与路由状态。结果显示所属密钥，系统令牌模式额外显示账户用户 ID。每个目标独立缓存，不跨账户合计；同一账户的多个密钥可能返回同一个余额，不应相加。

「渠道配置（原有）」保留以前保存的渠道级设置和接口，供已有部署继续使用；不会自动分配给某个密钥，也不表示该渠道全部密钥属于这个账户。多账户场景应逐个选择密钥设置。

插件配置只用于非敏感参数，以普通 JSON 存储并可由管理员读回，不能填写 API Key 或系统访问令牌。凭据来自渠道已有的加密密钥，或单独加密保存的额度查询凭据。

New API 选择「系统访问令牌 + 用户 ID」，填写目标账户在上游生成的系统访问令牌及其用户 ID，保存后手动查询。请求使用 Authorization: Bearer 与 New-Api-User，返回账户 ID 必须与配置一致。系统访问令牌只供额度查询，不进入模型转发密钥池；查询失败时不会换用模型密钥。界面可替换或显式清除已保存令牌，留空保留原值，后端不会回显明文或密文。查询结果显示凭据来源和账户用户 ID。

已有渠道默认保留原查询方式；首次选择 New API 时默认展示系统访问令牌方式。较新上游若支持通过模型令牌查询账户额度，也可选择「渠道模型密钥」。

官方插件默认将渠道 baseUrl 末尾的 /v1 或 /v1beta 去掉，作为站点地址；Sub2API 随后追加 /v1/usage，New API 按模式追加 /api/user/self 或 /api/usage/token/，并读取 /api/status。子路径部署可显式覆盖，例如渠道为 https://example.com/proxy/v1，实际站点在 /gateway：

~~~json
{"baseUrl":"https://example.com/gateway"}
~~~

该覆盖地址必须与渠道地址同源（协议、域名、有效端口相同），且不得带用户名、密码、查询参数或片段。官方插件不会跟随 HTTP 重定向，避免把凭据发送到其他站点。

服务器无法直连上游时，两个官方插件均可在渠道的插件 JSON 配置中指定可选的 `proxyUrl`。仅该渠道的额度查询使用此代理，包括 New API 的账户和状态接口；模型请求及其他渠道不受影响。例如：

~~~json
{"proxyUrl":"http://proxy.internal:7897"}
~~~

代理地址必须使用 `http://`，可用于 HTTP 转发和 HTTPS CONNECT，端口默认 80。仅支持无认证的可信代理，不得包含用户名、密码、子路径、查询参数或片段。省略、null 或空字符串使用原有直连行为。地址由网关服务器解析；Docker 中的 `127.0.0.1` 指向容器自身，访问宿主机代理需填写容器可达的宿主机地址。查询完成后释放临时代理客户端，不缓存凭据或按渠道无限创建常驻客户端。

官方查询接口固定使用 Bearer 鉴权，不沿用模型转发的请求头或请求体覆盖。特殊鉴权和私有额度接口可通过自定义 Provider 支持。

### 上游限制

- Sub2API 钱包使用明确的 balance 字段，允许 0 和负余额；订阅使用共享 subscription 的 remaining，-1 显示为无限制。套餐额度是当前密钥所属订阅池，可能受日、周、月窗口约束。
- Sub2API 在密钥有额度或速率限制时，可能仅返回 mode=quota_limited 和密钥 quota。这不足以确定账户共享余额，插件会返回 UNSUPPORTED；可用无此类限制的上游密钥查询。
- New API 系统访问令牌模式使用 /api/user/self 的 data.quota 和 data.used_quota。用户 ID 必须是正整数并与令牌所属账户一致，New-Api-User 不是任意账户选择器。部分版本使用 HTTP 200 表示认证失败，插件同样检查 success，不会将失败中的字段当成余额。
- New API 模型密钥模式仅适用于返回 data.user_quota 的上游版本；有些旧版（例如 v0.13.2）没有此能力，应使用系统访问令牌模式。插件忽略单令牌 total_available 和 unlimited_quota。
- New API 按 quota_per_unit 与 quota_display_type 换算 USD、CNY 或自定义币种。兼容旧版在缺少 quota_display_type 时使用 display_in_currency=true 表示美元的格式，按 quota / quota_per_unit 换算；新版显式币种优先。缺少有效换算参数、币种不明确或状态接口不可用时，保留原始数值并标注 quota 单位，不擅自当成美元。
- 两个适配器基于公开上游接口；分支或旧版本可能没有对应字段。无法确认共享额度时会说明不支持，不会将缺失字段当成 0。

## 查询行为与 API

所有端点都需要管理员会话；POST、PUT 还要求 X-CSRF-Token。

| 方法与路径 | 用途 |
| --- | --- |
| GET /admin/api/quota-providers | 返回已加载 Provider 的 id、displayName、apiVersion 数组 |
| PUT /admin/api/channels/{id}/quota-settings | 完整替换该渠道的额度设置，返回更新后的渠道 |
| POST /admin/api/channels/{id}/quota | 手动获取渠道额度快照，响应 Cache-Control: no-store |
| PUT /admin/api/channels/{id}/keys/{keyId}/quota-settings | 完整替换指定密钥的额度设置，返回更新后的渠道 |
| POST /admin/api/channels/{id}/keys/{keyId}/quota | 只查询指定密钥绑定的账户，响应 Cache-Control: no-store |

设置请求：

~~~json
{"providerId":"new-api","config":{}}
~~~

系统访问令牌模式请求（示例中的凭据是占位符）：

~~~json
{
  "providerId":"new-api",
  "credentialSource":"DEDICATED",
  "config":{"authMode":"system","userId":"123"},
  "credential":"<系统访问令牌，不含 Bearer 前缀>"
}
~~~

credentialSource 默认 CHANNEL_KEY，DEDICATED 表示只使用独立查询凭据。credential 为只写字段，最多 4096 个字符，不含控制字符；省略、null 或空白表示保留。clearCredential: true 显式清除，不能与非空 credential 同时提交。读取渠道的 quota 仅增加 credentialSource、credentialSet，不返回凭据。凭据使用网关主密钥进行 AES-GCM 加密，独立于 quota_settings JSON 存储。上述字段也适用于创建、更新渠道请求中的 quota；仅更新普通渠道信息或省略凭据不会清除已保存令牌。

密钥级设置端点使用相同请求格式。读取渠道时，每个 `keys[].quota` 为该密钥的设置与 `credentialSet`，从未配置为 null；不回显凭据。密钥级查询的 `scope` 为 `KEY`，并返回 `targetKeyId`（包括独立系统令牌模式的归属）；`queryKey` 仍只代表实际使用的模型密钥，因此独立令牌模式下为空。未配置的密钥返回 DISABLED，不调用其他账户。不存在或不属于该渠道的 keyId 返回 404。编辑渠道及密钥常规信息会保留这些设置；删除密钥会级联删除其额度配置和独立凭据。

New API 的 authMode 默认 token，system 则使用系统账户接口，userId 可为正整数或其十进制字符串。切换查询认证方式不会自动删除已保存的独立令牌。

providerId 为空表示关闭；允许保存尚未安装插件的 ID，安装并重启后生效。config 必须是对象，UTF-8 编码不超过 8 KiB。渠道创建、更新接口同样接受可选 quota 字段；更新省略 quota 会保留原设置，quota: {} 则关闭。

成功查询示例（金额使用十进制字符串，不经过浮点数）：

~~~json
{
  "channelId":"channel-id",
  "providerId":"new-api",
  "status":"AVAILABLE",
  "scope":"CHANNEL",
  "remaining":"100.25",
  "used":"20",
  "unit":"USD",
  "checkedAt":1793404800000,
  "sharedKeyCount":3,
  "cached":false,
  "queryKey":{"id":"key-id","name":"自有密钥"},
  "attemptedKeys":[{"id":"key-id","name":"自有密钥"}]
}
~~~

可选字段还包括 limit、planName、message、credentialSource；无值时省略或为 null。checkedAt 是网关完成本次查询的毫秒时间戳，命中缓存时保持不变。sharedKeyCount 保留兼容，表示渠道关联密钥总数，不证明它们共享余额，也不表示发起了同样数量的查询。queryKey 和 attemptedKeys 只包含密钥的 ID、名称，不含凭据；独立凭据模式下两者为 null 和空数组，credentialSource 为 DEDICATED。没有实际调用插件时来源为空，查询失败或超时则保留已经使用的来源。

快照状态为 AVAILABLE、UNLIMITED、UNSUPPORTED、AUTHENTICATION_FAILED、RATE_LIMITED、ERROR 或 DISABLED。上游查询失败仍返回 HTTP 200 的快照，方便界面区分错误类型；管理端鉴权、无效设置、渠道不存在沿用网关的 401/403、400、404 错误格式。

核心选择已设置凭据且当前可用的密钥，按优先级、名称、ID 排序。仅鉴权失败时换下一个密钥重试一次；余额为 0、限流或其他错误均不换密钥。查询不调用路由失败处理器，不触发冷却或自动禁用。

上述选钥与重试仅适用于 CHANNEL_KEY；DEDICATED 不要求存在可用模型密钥，也绝不回退到它们。未保存独立凭据时返回明确错误，不发送上游请求。更换、清除凭据或切换来源会使下一次查询重新执行。

缓存按渠道、目标密钥和配置保存 60 秒，包含插件已执行完成的失败结果；查询超时和执行器繁忙不写入缓存，可立即重试。配置、地址或凭据变化后重新查询。相同目标的并发查询合并，缓存最多 1024 项。插件调用使用专用的最多 4 线程执行器，繁忙时立即返回错误；每次查询最多 45 秒，超时或客户端取消会中断插件线程。官方插件 HTTP 连接超时最多 10 秒、请求及响应等待最多 15 秒、响应体最多 1 MiB。

## 开发自定义插件

使用 Java、Kotlin 或其他 JVM 语言实现 dev.soven.llmgateway.spi.quota.ChannelQuotaProvider，提供公开无参构造函数。核心发布的轻量编译制品为：

~~~xml
<dependency>
  <groupId>dev.soven</groupId>
  <artifactId>llm-gateway</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <classifier>quota-spi</classifier>
  <scope>provided</scope>
  <exclusions>
    <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
  </exclusions>
</dependency>
~~~

先在核心项目运行 mvn install，供本机 Maven 解析该制品。Gradle 使用同坐标的 compileOnly 依赖，并排除传递依赖。不要将 SPI、Kotlin 标准库或网关核心类重复打包到插件中。网关使用父优先类加载；额外私有依赖应一并打包，必要时重定位以避免与其他插件冲突。

最小实现：

~~~kotlin
package example

import dev.soven.llmgateway.spi.quota.*

class ExampleQuotaProvider : ChannelQuotaProvider {
    override val id = "example"
    override val displayName = "Example upstream"

    override fun query(request: ChannelQuotaQuery): ChannelQuotaResult {
        // 在此调用上游并校验响应，然后返回共享账户的额度。
        // 完整可运行的 HTTP 实现见 extensions/quota-providers。
        return ChannelQuotaResult(
            status = ChannelQuotaStatus.UNSUPPORTED,
            message = "请实现该上游的账户额度接口",
        )
    }
}
~~~

在 src/main/resources/META-INF/services/dev.soven.llmgateway.spi.quota.ChannelQuotaProvider 文件中写入：

~~~text
example.ExampleQuotaProvider
~~~

ID 必须匹配 [a-z0-9][a-z0-9._-]{0,63}，且唯一。displayName 为不超过 120 字符的非空名称，不能含控制字符。当前 apiVersion 为 1。参考官方插件的独立 pom.xml 构建 JAR，再按上述目录加载，不需要修改核心代码。

query 接收 channelId、baseUrl、authType、credential 和 configJson；返回状态及可选的 remaining、used、limit（BigDecimal）、unit、planName、message。AVAILABLE 必须有 remaining；负余额允许，used 和 limit 不能为负。失败结果不得携带金额。金额精度最多 40 位，scale 在 -20..20，避免异常上游数据进入界面。

插件实例在渠道间共享，query 必须线程安全、响应中断并自行限制网络与响应大小。不要启动长期后台任务；网关关闭时会调用 close。插件不得记录、缓存、持久化或返回凭据，也不要返回完整上游响应或异常文本；核心额外对已用凭据和展示文本做脱敏与长度限制。

这是可信 JVM 插件机制，具有与网关进程相同的系统权限，不是沙箱。只安装可信来源的 JAR；接口约束不会阻止恶意插件访问文件或网络。第一版不提供在线上传、热更新、插件市场或任意自定义前端，配置与结果统一由网关界面展示。
