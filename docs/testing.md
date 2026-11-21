# 后端测试与 CI 执行

PrismLLM 仍是模块化单体；本轮调整迁移资源与测试归属，不引入服务或改变运行镜像的职责。

## 本地执行

第一次验证或源码变更后，使用完整生命周期，确保生产代码与测试都被编译：

```bash
# 首次验证或官方诊断插件源码变更时，先安装当前 SDK / host 并独立构建真实包。
mvn --batch-mode --no-transfer-progress -DskipTests \
  -pl prism-plugin-sdk,prism-plugin-host -am install
mvn --batch-mode --no-transfer-progress \
  -f extensions/connectivity-diagnostic/pom.xml verify
PRISM_TEST_PLUGIN_CATALOG_DIR="$PWD/extensions/connectivity-diagnostic/target" \
  mvn --batch-mode --no-transfer-progress test \
  -Dprism.plugin.fixtureDirectory="$PWD/extensions/connectivity-diagnostic/target"
```

官方连通性扩展不在根 reactor 中，也不进入 app 测试 classpath。`verify` 生成同目录的 `catalog.json` 与 `prism-connectivity-1.0.0.prismplugin`。宿主组件测试通过 `prism.plugin.fixtureDirectory` 读取真实包；app 通过 `PRISM_TEST_PLUGIN_CATALOG_DIR` 读取同一目录，该变量不是运行时的 `PLUGIN_CATALOG_DIR`。已有有效夹具且插件源码未变时，可只执行上面的最后一条测试命令。不要在测试读取夹具期间清理或重新构建同一个扩展 `target` 目录；独立构建和冷加载边界见 [官方诊断插件指南](plugins/connectivity-diagnostic.md)。

共享集成夹具默认使用空插件目录。需要官方诊断包的测试类显式覆盖 `requiresConnectivityPluginPackage = true`，并在启动数据库前检查真实产物；无关测试的定向执行不要求先构建扩展。完整回归和专用包加载验收仍需执行上述独立构建。

CI 在同一个 job 中先编译、测试编译、安装当前 checkout 的模块与插件 SPI，再运行已编译测试；app 执行前独立构建官方诊断包，并在宿主中验证实际产物：

```bash
mvn --batch-mode --no-transfer-progress -DskipTests install
mvn --batch-mode --no-transfer-progress \
  -pl prism-test-support,prism-shared,prism-plugin-sdk,prism-plugin-host,prism-protocol,prism-channel,prism-routing,prism-resilience,prism-subscription-oauth,prism-quota,prism-pricing,prism-request-log,prism-admin \
  surefire:test -DfailIfNoTests=true
mvn --batch-mode --no-transfer-progress \
  -f extensions/connectivity-diagnostic/pom.xml verify
mvn --batch-mode --no-transfer-progress -pl prism-plugin-host surefire:test \
  -Dtest=OfficialPluginPackageComponentTest -DfailIfNoTests=true \
  -Dprism.plugin.fixtureDirectory="$PWD/extensions/connectivity-diagnostic/target"
# CI 验证该类的 Surefire XML 有用例且零跳过、失败或错误。
PRISM_TEST_PLUGIN_CATALOG_DIR="$PWD/extensions/connectivity-diagnostic/target" \
  mvn --batch-mode --no-transfer-progress -pl prism-app surefire:test -DfailIfNoTests=true
```

`-DskipTests` 只跳过执行，不跳过测试编译。直接调用 `surefire:test` 不会编译；**源码或测试发生变更后必须重新编译，不能复用其他提交或其他 job 的 target 目录**。CI 只缓存 Maven 依赖，不缓存测试编译产物。扩展仍通过独立 POM 的 `verify` 验证。

`prism-app` 的 Surefire 测试子 JVM 默认通过 `argLine=-Xmx3g` 限定最大堆，本地与 CI 使用同一配置；Maven 自身的 `MAVEN_OPTS` 不会为这个子进程设置堆大小。需要额外 JVM 参数或调整内存时，使用 `-DargLine='-Xmx3g ...'` 覆盖完整参数并保留合适的堆上限。堆耗尽或子进程异常退出仍判定为失败，不能以退出前已完成测试的零失败计数替代 Maven 成功退出。

共享网关集成夹具与停机测试的子 JVM 显式关闭 `MODEL_PRICE_CATALOG_ENABLED`，避免后台预热或管理页惰性查询访问真实 models.dev；需要目录行为的用例使用 MockEngine 或本机 HTTP 夹具。运行时该项默认开启，关闭不影响内置官方基准价。OAuth、模型与其他上游同样仅使用合成凭据和模拟端点。

## 迁移资源与测试归属

根 reactor 先构建 `prism-db-migrations`、`prism-test-support`，再构建上游模块（包含新增的 `prism-plugin-sdk`、`prism-plugin-host`）与 `prism-app`；官方连通性扩展保持独立 POM，不加入 reactor：

- `prism-db-migrations` 是仅含资源的 jar，没有可执行测试。SQL 的唯一源目录是 `prism-db-migrations/src/main/resources/db/migration`；app 以 compile 依赖打包这些资源，Flyway 的运行时位置仍为 `classpath:db/migration`，不维护第二份 SQL。
- `prism-test-support` 是普通 jar，不是 test-jar；它的生产源码提供测试数据库与未过滤 JUnit discovery 工具，自身有不启动数据库的真实契约测试。它只依赖迁移资源和第三方库，不反向依赖领域或 app。app 与下面四个领域模块仅通过 test scope 使用它，不进入运行时依赖闭包。
- `prism-plugin-sdk` 与 `prism-plugin-host` 是有可执行测试的正常模块；独立诊断扩展只以 provided scope 编译依赖 SDK，不依赖 app 或 host 内部实现。
- 完整 upstream 测试列表以 `prism-test-support` 开头，包含 SDK / host，不包含无测试的迁移模块；`-DfailIfNoTests=true` 仍用于所有实际选择的测试模块。

下面四个 `component` 类移到仓储所属模块，合计保留原有 14 个用例：

| 所属模块 | 测试类 | 原有用例数 |
|---|---|---:|
| `prism-request-log` | `RequestLogGroupedCostsTest` | 1 |
| `prism-admin` | `WebSearchSettingsRepositoryTest` | 3 |
| `prism-quota` | `ClientKeyQuotaRepositoryTest` | 6 |
| `prism-subscription-oauth` | `OAuthWindowRepositoryTest` | 4 |

迁移的是 OAuth **窗口**仓储测试，`OAuthAccountRepositoryTest` 等其余 app 仓储测试保留原位。迁移后的类使用共享测试基础设施，在真实 PostgreSQL 中执行**完整 Flyway schema**并克隆隔离数据库，不以缩减表结构或手工建表替代。数据库连接与仓储按类释放，共享容器只在同一个测试 JVM 内复用；不同模块的 Surefire JVM、不同 Maven 执行或 job 之间不共享容器。

## app 测试分类

每个可执行 app 测试的有效类别标签必须恰好一个：

| 标签 | 范围 |
|---|---|
| `unit` | 不启动数据库、浏览器或真实网络服务的局部逻辑与编排验证 |
| `component` | 仓储与迁移、Ktor 测试宿主、本机 HTTP/Netty、动态编译或插件加载等组件验证 |
| `browser` | 需要实际 Chrome/Chromium 的渲染验证 |
| `integration` | 装配完整网关，验证 HTTP、路由、额度、日志等跨模块行为 |

类别描述以整个测试类为单位；一个类同时有纯逻辑与组件用例时，标记其所需最重类别。数据库测试不自动等于完整网关集成测试。

`channel`、`protocol`、`shard-*` 及方法级领域标签是正交维度，不是类别；本轮保留原有标记。新增类别**尚不用于扩大 CI 排除范围**。浏览器测试仍执行；环境缺少浏览器造成的跳过及测试失败会在报告中体现，不能当作提速。

分类契约测试通过共享 JUnit Platform 未过滤 discovery 检查有效标签，包括继承标签及测试模板。app 扫描本模块的测试编译目录；四个接收组件测试的领域模块门禁仅显式选择迁移类做未过滤发现，不强制已有领域测试全部分类。两者都不扫描依赖 jar 的测试。它只发现、不执行被检查的测试，因此不会额外启动数据库、浏览器或完整网关。app 新增测试以及迁移类中的新增用例必须通过各自门禁。

## CI 选择与兜底

- PR 根据模块变更选择本模块及实际依赖受影响的下游测试；`protocol/channel → routing`、`protocol/pricing → request-log`，以及 `SDK → host → app`、`host → app`。宿主不传播到 admin；安装记录仓储留在 admin，跨模块适配由 app 组装。选择脚本回归测试将全部 reactor 模块（含迁移与支持基础设施、SDK / host）和可执行测试分区分别对照实际 POM，依赖图包括 test scope；新增依赖须同时更新传播规则。
- 迁移后的组件用例随所属模块及上述实际依赖关系选择，不因 `app_tests=true` 无条件加入四个目的模块。仅 app 变更仍执行 app 全量，但不会运行已移走的无关仓储组件测试；pure channel 不选择这些仓储模块，protocol 因真实测试依赖选择 request-log。
- 未混入要求 app 全量的变更时，protocol/channel 仍按原标签裁剪无关 `integration`；SDK / host 或扩展变更加入 channel 回归范围，与 protocol 混合时为 `channel|protocol`。app 中保留的 `unit/component/browser` 仍全量执行。其他领域模块、app、shared、根构建或本轮 CI 执行脚本变更执行 app 全量。
- `prism-plugin-sdk/**`、`prism-plugin-host/**`、`extensions/**` 分别通过 `CHANGED_PLUGINSDK`、`CHANGED_PLUGINHOST`、`CHANGED_EXTENSIONS` 进入选择。独立扩展变更在 POM 图之外显式选择 host 包边界测试及 channel 相关 app 集成，不因此加入 admin / support 或无关 upstream；每次选中 app 都先独立 `verify` 官方诊断包，再定向运行真实包宿主组件测试，最后使用同一目录夹具执行 app。可选额度扩展保留独立 `verify`，不把扩展单独变更无条件升级为全部慢速 app 集成。
- `prism-db-migrations/**` 与 `prism-test-support/**` 直接属于 backend/build 范围，沿用 `CHANGED_BUILD`，不增加这两个模块的 flags/env。shared/build 变更执行全部可执行 upstream（含 support 契约、SDK / host）、app 与可选额度扩展，并验证官方诊断包夹具。
- 涉及后端的 main push 完整回归行为不变：执行全部可执行 upstream（含 SDK / host）、官方诊断包 `verify` / 真实夹具 app 测试及可选额度扩展，不按协议/渠道裁剪，四个迁移类也全部执行。
- 本轮 CI 执行规则自身变更属于 backend/build 范围，确保修改 workflow 的 PR 也经过后端回归。
- 输入必须为字面值 `true/false`，未知标签或缺失/非法输入失败，不静默跳过。

app 保留的非集成测试和相关集成测试在同一次 Surefire 执行中运行；使用共享 helper 的测试只在同一个测试 JVM 内复用 PostgreSQL 模板容器，按类克隆独立数据库。此复用不跨模块、Maven 执行或 job。

## 报告与覆盖核对

CI Summary 展示准备构建、upstream（含 support 契约）、app、扩展阶段的 wall time 与退出码，以及每模块用例、失败、错误、跳过和测试类耗时。无报告时明确显示未执行，不显示为零测试通过。迁移模块无可执行测试，不应伪造 Surefire 成功报告；现有 `prism-*` 报告扫描会覆盖新的支持模块与迁移后的领域测试，无需另设报告分支。

`backend-test-reports` artifact 保留 7 天，包含：

- `target/ci-test-timings.json`：阶段时间及退出码；
- `target/ci-test-report.json`：测试身份集合、状态及模块汇总，不复制测试输出或请求正文；
- 清理后的 Surefire/Failsafe XML：保留测试身份、状态、计数和耗时，移除 properties、stdout/stderr、失败正文及错误消息。原始报告只在 runner 中保留，不上传。

CI 既有控制台日志仍可能包含测试输出；本轮新增摘要与 artifact 不额外发布这些正文。失败原因仍可从对应运行日志查看。

测试类耗时之和不等于 Maven wall time；筛选报告只包含所选用例，`Skipped=0` 不代表全量执行。CI 使用干净 checkout；本地在同一 target 中反复切换筛选范围时，Surefire 会留下未重写的旧 XML，汇总前应先移走旧报告目录，不能把残留报告当作本轮覆盖。完整回归应将四个迁移类在新模块中的 14 个用例与原有身份对应核对，共享支持和分类契约等新增门禁另计。本指南不承诺提速：若比较耗时，须使用相同变更场景和同类 runner，并确认没有删除用例、增加跳过或降低浏览器覆盖。
