# 官方连通性诊断插件与开发契约

官方插件 `prism.connectivity` 的包版本是 **1.0.0**，独立于网关与编译 SDK 的 `0.1.0-SNAPSHOT`。`schemaVersion`、`apiVersion` 当前均为 1。它是管理员明确信任的 JVM 插件，不是浏览器脚本、热加载插件市场或安全沙箱。

## 构建与本地目录

需要 JDK 21 与 Maven。所有命令在仓库根目录执行；扩展不在根 reactor 中，不能只运行根目录 `mvn package` 就期望得到插件包。

```bash
# 安装当前源码的版本化 SDK / host；不需要构建完整 app。
mvn --batch-mode --no-transfer-progress -DskipTests \
  -pl prism-plugin-sdk,prism-plugin-host -am install

# 独立编译、执行纯 SDK 上下文单元测试并生成安装包和本地可信目录索引。
mvn --batch-mode --no-transfer-progress \
  -f extensions/connectivity-diagnostic/pom.xml verify

# 将索引与其引用的包一起放入部署方管理的可信目录。
mkdir -p bundled-plugins
cp extensions/connectivity-diagnostic/target/catalog.json \
   extensions/connectivity-diagnostic/target/prism-connectivity-1.0.0.prismplugin \
   bundled-plugins/
```

构建输出是 `extensions/connectivity-diagnostic/target/prism-connectivity-1.0.0.prismplugin` 与同目录的 `catalog.json`。ZIP **恰好**包含 `manifest.json`、`ui.json`、`plugin.jar` 三个普通文件，没有安装/卸载脚本、SQL、DDL 或前端资源。JAR 只包含插件实现、SPI 服务声明与构建元数据；SDK、Kotlin 标准库及 JSON 库由宿主提供，不重复打包。构建工具使用固定 JAR/ZIP 时间戳与稳定 ZIP 条目顺序，`catalog.json` 的小写 SHA-256 对应最终 ZIP 字节，不是内部 JAR 的散列。同一源码和固定工具链可重复构建；已发布安装的同版本包不可原地替换成另一个摘要。

外层 ZIP 与内部 JAR 只接受使用 Store 或 Deflate 的经典单卷 ZIP。宿主在调用 Commons Compress 前校验中央目录的范围和实际条目数（目录最多 8 MiB、外层最多 3 条、JAR 最多 10,000 条），拒绝 ZIP64、多卷、加密和其他不支持的格式。JAR 中若有 manifest，路径必须精确写作 `META-INF/MANIFEST.MF`，不得声明外部 `Class-Path`。宿主也会校验条目路径、展开大小、压缩率、CRC、中央与本地头的一致性、条目重叠和包 SHA-256。

| 配置 | 本地默认目录 | Docker / Compose |
|---|---|---|
| `PLUGIN_CATALOG_DIR` | `bundled-plugins` | `/app/bundled-plugins`，镜像提供可信索引与包 |
| `PLUGIN_INSTALL_DIR` | `plugin-state` | `/app/plugin-state`，独立可写持久卷 |
| 旧的 IQ / 额度 JAR 目录 | `plugins` | 保留 `./plugins:/app/plugins:ro`，不与托管目录混用 |

本地运行示例：

```bash
# app 集成测试使用真实、独立构建的插件包；该变量只供测试夹具使用。
PRISM_TEST_PLUGIN_CATALOG_DIR="$PWD/extensions/connectivity-diagnostic/target" \
  mvn --batch-mode --no-transfer-progress -pl prism-app -am package

PLUGIN_CATALOG_DIR="$PWD/bundled-plugins" PLUGIN_INSTALL_DIR="$PWD/plugin-state" \
  java -jar prism-app/target/prism-llm-0.1.0-SNAPSHOT.jar
```

数据库、管理员凭据、主密钥与前端静态资源的准备仍见 [README](../../README.md) 和 [配置指南](../configuration.md)。可通过 `-Dmaven.repo.local=/path/to/isolated-m2` 为上面的每条 Maven 命令指定同一独立缓存；SDK 安装与插件构建必须使用同一个缓存。不要并发构建相同模块的 `target` 目录。

Docker 分开构建网关核心（包含 SDK / host）与独立扩展；最终镜像将索引与 `.prismplugin` 放在 `/app/bundled-plugins`，**不把插件实现 shade 进 `app.jar`**。Compose 的 `pluginstate` 卷保存托管包，数据库保存期望安装版本、启用状态、权限、配置与卸载记录。需要自定义本地可信目录时，由部署方同时提供 `catalog.json` 与引用包，并只读挂载到所配置的目录；修改环境变量时也必须同步修改卷路径。不要把可由普通请求写入的目录作为可信目录，也不要靠删除托管卷重置数据库中的卸载记录。

## 管理员安装、启用与冷加载

「插件」页使用通用管理 API，不通过插件 ID 分支调用旧测试 helper。只有本地可信索引列出的兼容包可安装；安装操作只校验、暂存文件并保存期望状态，**不会执行插件代码**。当前进程只在启动时加载期望启用的包。

第一次启动且数据库从未存在该官方插件记录时，宿主可从本地可信索引一次性引导安装、授予已知诊断权限并启用，维持原有连通性入口。仅在没有记录且存在兼容引导包时才读取旧默认提示词；读取失败只影响可选引导，不阻断其他托管插件加载。首次手动安装也在创建记录的同一 CAS 流程中迁移旧提示词，默认仍禁用；已有安装、升级和卸载 tombstone 不会重新读取或覆盖旧配置，并发写入的记录优先。若索引不存在、包不兼容或加载失败，连通性入口不可执行，但不会改用 app 内置的另一份实现。

手动操作顺序：

1. 读取本地目录与状态，核对 ID、版本、来源、摘要及权限。
2. 安装所选版本并明确确认完整权限。**新的手动安装默认禁用**；升级只有在确认新版本完整权限后才保留期望启用状态。同一版本/摘要的重复安装是幂等操作。
3. 启用后观察 `desiredEnabled=true`、`requiresRestart=true`；在下一次进程启动之前，不能认为已经可执行。
4. 本地重新启动网关进程，或对已具备该镜像/目录的 Compose 部署执行 `docker compose restart gateway`。随后读取状态，确认 `installedVersion`、`activeVersion` 与 `admissionEnabled`；仅 `activeUi` 的动作可执行。
5. 禁用会立即关闭新的执行准入，不需要重启；已发出的上游 HTTP 探测不保证被撤回。即使旧类仍在进程中，重新启用也只在下一次启动开放。
6. 卸载会写入 `installed=false` 的 tombstone 并立即关闭准入，保留配置与包选择用于显式恢复；不是删掉数据库记录。之后重启或更新镜像不会自动重新引导安装。恢复需管理员重新安装、授权、启用并重启。

安装新版本不会在本进程切换代码或执行元数据：`installedVersion` 是期望版本，`activeVersion` 是已加载版本。升级等待重启时，仍显示并使用当前活动版本的 UI。调用携带 `expectedActiveVersion`，陈旧界面不会悄悄执行另一版本。

所有以下接口都要求现有管理员会话；写操作还要求现有 `X-CSRF-Token`，不会接受数据面 Bearer 密钥。

| 方法与路径 | 用途 |
|---|---|
| `GET /admin/api/plugin-catalog` | 只读本地可信包的 manifest、UI、摘要、兼容性 |
| `GET /admin/api/plugins`、`GET /admin/api/plugins/{id}` | 期望状态、活动版本、准入、重启与错误状态 |
| `GET /admin/api/plugins/ui` | 仅返回当前活动且准入的通用 UI 贡献 |
| `PUT /admin/api/plugins/{id}/installation` | 安装指定版本，提交完整 `approvedPermissions` |
| `PUT /admin/api/plugins/{id}/enabled` | 更新期望启用状态；启用提交明确批准的权限 |
| `PUT /admin/api/plugins/{id}/configuration` | 更新普通配置 `{ "values": { ... } }` |
| `DELETE /admin/api/plugins/{id}` | 写入卸载 tombstone，保留恢复所需选择与配置 |
| `GET /admin/api/plugins/{id}/resources/model-test-targets` | 按授权上下文提供安全结构化模型、凭据、协议选择 |
| `POST /admin/api/plugins/{id}/invocations` | 执行活动描述符动作；绑定当前版本与已授权目标 |

安装请求体：

```json
{
  "version": "1.0.0",
  "approvedPermissions": [
    "diagnostics.channel.probe",
    "diagnostics.oauth-account.probe"
  ]
}
```

启用请求体在同一完整权限列表之外增加 `"enabled": true`；禁用使用 `{"enabled":false}`。配置示例是 `{"values":{"defaultPrompt":"请只回复 OK"}}`。配置为管理员可读回的普通 JSON，不能存放密钥、access token、任意地址或可执行表达式。

## 检测动作、设置与结果边界

官方 UI 有三个声明式入口：

| placement / action | 上下文与字段 |
|---|---|
| `channel-tests` / `probe-channel` | `channel`；模型、凭据、协议、single/all 范围、本次提示词 |
| `oauth-account-tests` / `probe-account` | `oauth-account`；账号模型、原生协议、本次提示词；**没有渠道密钥选择器、跨账号范围或批量动作** |
| `plugin-page` / `probe-global` | 安全模型目标所绑定的 `channel`；模型、凭据、协议、single/all 范围、本次提示词 |

模型与凭据选项来自宿主的 `model-test-targets` 结构化资源，只含名称、不透明 ID 与支持协议。账号模式使用当前账号自己的原生授权，保持 Antigravity 等已有供应商信封、端点与授权处理；不能把渠道密钥误当账号 token。描述符没有回调、表达式、任意 URL 或自定义前端代码。

提示词按顺序选择：去除首尾空白的本次 `prompt`，再取上下文配置的非空 `defaultPrompt`，最后使用精确默认值 **`请只回复 OK`**。去除首尾空白后最多 **4000 个字符**，超长报错，不截断为另一个探测内容。本次覆盖不改变保存设置。活动与期望版本相同时，普通默认提示词配置对下一次调用立即生效；等待升级重启的配置不会提前替换当前活动快照。

每个目标的 HTTP 探测最多输出 **128 Token**、等待 **45 秒**，并遵守宿主提供的更严格限制。原生 OAuth 的端点准备、代理校验、凭据刷新、项目发现和请求组装共享独立的 **20 秒**准备期限；诊断用数据库语句也使用剩余预算。准备超时返回 `504 / oauth_preparation_timeout`，不会继续发起模型探测。渠道批量操作最多两路并发，无自动重试；账号入口只检测当前目标。插件只返回 `ProbePlan(prompt,maxOutputTokens,timeoutMs)`，目标、URL、凭据解密、协议转换、真实 HTTP 请求以及超时限制仍由宿主绑定和执行。

报告保留宿主提供的成功/失败结论，显示安全状态码、非负耗时及白名单数字用量，不回显任意 usage JSON；文本去除控制/格式字符并限制展示长度，通用界面按纯文本转义。插件上下文不含明文凭据，宿主在提供 outcome 前负责凭据脱敏；这并不保证上游从未回显用户输入。上游 HTTP 401 是失败 outcome 的状态码，**不是**要求管理员重新登录的管理 HTTP 401。

检测不写生产请求日志、不消耗调用方密钥额度，也不触发路由冷却或自动停用；上游仍可能收费。成功只表示本次请求完成，不证明模型身份、质量或长期可用性。旧连通性接口沿用原最终 JSON，与新插件通用报告不同，但共同经过同一个活动运行时；智商、指纹及旧 IQ / 额度 JAR 扩展继续独立运行。

## 开发接口与测试

实现公开无参构造的 `dev.soven.prismllm.plugin.sdk.DiagnosticPlugin`，提供 `id`、`version`、`plan(input,context)` 与 `report(plan,outcome)`。同一个实例的 `plan` / `report` 可能并发执行，宿主也可能在尚有调用运行时触发 `close`；实现应保证线程安全，并允许关闭与待完成的工作重叠。宿主对每次插件代码调用都有界等待，但不能强制停止不合作的 JVM 代码，超时后调用仍可能在后台运行。注册文件为：

```text
META-INF/services/dev.soven.prismllm.plugin.sdk.DiagnosticPlugin
```

其中只声明实现的类名。SDK 编译依赖必须是宿主提供的契约，不引入 `prism-app`、`prism-admin`、host 内部类或整个网关依赖：

```xml
<dependency>
  <groupId>dev.soven</groupId>
  <artifactId>prism-plugin-sdk</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
  <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
</dependency>
```

官方独立 POM 见 `extensions/connectivity-diagnostic/pom.xml`。manifest 的 `defaultPrompt` 为 required textarea、默认 `请只回复 OK`、maxLength 4000；运行 UI 使用 SDK 字段种类白名单。每个动作必须声明规范且必填的 `model` 与 `protocol` 控件；若声明 `scope`，必填范围必须在动作声明的每个上下文中至少有一个可用值，OAuth 只能使用 `single`，非批量渠道不能使用 `all`。渠道批量动作由宿主逐目标调用，因此只要动作有 `scope` 控件就必须提供 `single`，即使该控件可选；`all` 只适用于批量渠道动作。包中的 manifest / UI 与 SPI 实现版本必须一致，包版本独立升级；不要通过修改已安装同版本文件绕过摘要验证。构建源码 `src/build/java/PackagePlugin.java` 仅在 Maven 构建中运行，不进入安装包。

扩展的 `unit` 测试仅使用假的纯 SDK 上下文与 outcome，覆盖提示词优先级、限制、声明式元数据、服务注册和安全报告，不访问真实上游。依赖按实际 POM 传播：SDK → host → app，host → app；host 不再传播到 `prism-admin`。从仓库根目录可按以下顺序独立构建真实官方包，并在 host 中对该产物运行组件测试：

```bash
mvn --batch-mode --no-transfer-progress -pl prism-plugin-sdk,prism-plugin-host -am -Dgroups=unit clean install
mvn --batch-mode --no-transfer-progress -f extensions/connectivity-diagnostic/pom.xml verify
mvn --batch-mode --no-transfer-progress -f prism-plugin-host/pom.xml \
  -Dtest=OfficialPluginPackageComponentTest \
  -Dprism.plugin.fixtureDirectory="$PWD/extensions/connectivity-diagnostic/target" \
  -DfailIfNoTests=true test
```

必须检查 `prism-plugin-host/target/surefire-reports/TEST-*OfficialPluginPackageComponentTest.xml`，确认组件测试 `tests > 0`、`skipped = 0` 且无失败；只看到 Maven 命令成功而测试被跳过不算验证通过。之后 app 的包加载/连通性集成测试通过 `PRISM_TEST_PLUGIN_CATALOG_DIR="$PWD/extensions/connectivity-diagnostic/target"` 使用同一真实包，不把扩展实现加入 app 测试 classpath。扩展单独变更选择 host 边界测试及 channel 相关 app 集成；非 integration 用例仍按原规则运行，不把扩展变更无条件升级成全部慢速集成测试。main 的完整回归仍覆盖所有模块与旧额度扩展。

CI 选择回归可单独运行：

```bash
python3 -I .github/scripts/test_ci_test_modules.py
```

## 信任限制

- 本地 `catalog.json` 是**部署方控制的信任配置**，不是网络下载列表或数字签名。SHA-256 校验只能证明所安装字节与已批准索引一致，不能证明来源、代码善意或安全性。索引、引用包、托管状态与目录权限必须由可信运维方维护。
- 只安装索引批准的 ID / 版本 / 摘要；宿主校验包路径、ZIP 布局、manifest 身份、SDK 兼容性、配置/UI schema、动作上下文与权限，并在启动时加载。安装本身不加载代码。
- 权限确认与 SDK 信息最小化是宿主管理约束，不是 JVM 沙箱。可信代码仍拥有网关进程的系统权限，恶意类可以绕开接口访问网络或文件。超时/中断也不能可靠终止不合作的 JVM 代码；不能据此开放任意第三方插件。
- MVP 不提供远程目录、在线安装、上传任意包、安装脚本、数据库迁移钩子、热替换、后台任务队列、SSE 或插件自带前端。这里只说明本地官方构建与可信部署，不发布外部目录。
