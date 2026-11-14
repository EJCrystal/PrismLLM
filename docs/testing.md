# 后端测试与 CI 执行

PrismLLM 仍是模块化单体；本轮只拆分测试执行职责，不引入服务或改变运行镜像。

## 本地执行

第一次验证或源码变更后，使用完整生命周期，确保生产代码与测试都被编译：

```bash
mvn --batch-mode --no-transfer-progress test
```

CI 在同一个 job 中先编译、测试编译、安装当前 checkout 的模块与插件 SPI，再运行已编译测试：

```bash
mvn --batch-mode --no-transfer-progress -DskipTests install
mvn --batch-mode --no-transfer-progress \
  -pl prism-shared,prism-protocol,prism-channel,prism-routing,prism-resilience,prism-subscription-oauth,prism-quota,prism-pricing,prism-request-log,prism-admin \
  surefire:test -DfailIfNoTests=true
mvn --batch-mode --no-transfer-progress -pl prism-app surefire:test -DfailIfNoTests=true
```

`-DskipTests` 只跳过执行，不跳过测试编译。直接调用 `surefire:test` 不会编译；**源码或测试发生变更后必须重新编译，不能复用其他提交或其他 job 的 target 目录**。CI 只缓存 Maven 依赖，不缓存测试编译产物。扩展仍通过独立 POM 的 `verify` 验证。

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

分类契约测试通过 JUnit Platform 未过滤 discovery 检查真实测试树，包括继承标签及测试模板；只发现、不执行被检查的测试，因此不会额外启动数据库、浏览器或完整网关。新增测试也必须通过该检查。

## CI 选择与兜底

- PR 根据模块变更选择本模块及受影响的下游测试；`protocol/channel → routing`、`protocol/pricing → request-log`。选择脚本回归测试对照实际 POM 的生产和测试依赖，新增依赖须同时更新传播规则。
- 只有纯 protocol/channel 变更可裁剪 app 的无关 `integration` 测试；`unit/component/browser` 仍全量执行。其他领域模块、app、shared、根构建或本轮 CI 执行脚本变更执行 app 全量。
- 涉及后端的 main push 执行全部领域模块、app 及可选额度扩展，不按协议/渠道裁剪。
- 本轮 CI 执行规则自身变更属于 backend/build 范围，确保优化 workflow 的 PR 也经过后端回归。
- 输入必须为字面值 `true/false`，未知标签或缺失/非法输入失败，不静默跳过。

app 的非集成测试和相关集成测试在同一次 Maven 执行中运行，复用共享 PostgreSQL 容器，避免为每个类别重复启动。

## 报告与提速验收

CI Summary 展示准备构建、领域、app、扩展阶段的 wall time 与退出码，以及每模块用例、失败、错误、跳过和测试类耗时。无报告时明确显示未执行，不显示为零测试通过。

`backend-test-reports` artifact 保留 7 天，包含：

- `target/ci-test-timings.json`：阶段时间及退出码；
- `target/ci-test-report.json`：测试身份集合、状态及模块汇总，不复制测试输出或请求正文；
- 清理后的 Surefire/Failsafe XML：保留测试身份、状态、计数和耗时，移除 properties、stdout/stderr、失败正文及错误消息。原始报告只在 runner 中保留，不上传。

CI 既有控制台日志仍可能包含测试输出；本轮新增摘要与 artifact 不额外发布这些正文。失败原因仍可从对应运行日志查看。

测试类耗时之和不等于 Maven wall time；筛选报告只包含所选用例，`Skipped=0` 不代表全量执行。CI 使用干净 checkout；本地在同一 target 中反复切换筛选范围时，Surefire 会留下未重写的旧 XML，汇总前应先移走旧报告目录，不能把残留报告当作本轮覆盖。提速需要在相同变更场景、同类 runner 上比较阶段时间，并核对完整回归测试身份集合未减少。新增门禁测试另计，不通过删用例、增跳过或降低浏览器覆盖获得提速。
