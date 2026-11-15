# 订阅账号模型授权与映射：交付与验证记录

## 基准与范围

- 实施与整合日期：2026-11-14 至 2026-11-15。
- 功能分支：`feat/oauth-canonical-mappings`。
- 原实施基准：`a8d4b6105145c1abd8b7d1a9aef8ac9c433d412c`。
- 第一次整合冻结 main：`83f4d5d178401a84de9db65bd739821ff43bac2a`，包含调用方访问白名单（#241）及 IQ 视觉判分修复。
- 发布前核对发现远端前移到 `41b799d18e3b8c58234a4be03aff596e865b1443`（调用方访问黑名单 #242 与 IQ 后台任务），其新增 `V69__client_key_access_denylist.sql` 与本功能迁移重号，按同一授权执行第二次整合。
- 第三次整合：远端再前移到 `3a56ccf226ac938bf936da5488d53eb38ed5e427`（IQ 任务持久化 7e6e4f45 与 #243 白名单级联），其 `V70__iq_test_jobs.sql` 与本功能迁移再次重号，功能迁移改名 V71 并以 merge 方式整合；期间还修复 main 遗漏的分类登记。
- 两次 stash 检查点与本地提交历史均保留。**每次整合后的自动化、产物和两轴复核各自完成；GUI 实际结果另列，旧基准结果不作为新整合的通过证明。**
- 本轮获准验证后提交、推送并创建 PR；不自动合并或部署。原工作区的采样代码、配置与备份不参与本分支；不读取真实 `.env`、凭据或请求日志，不访问部署端口或部署数据库。

## 实现

- 在订阅账号行内原有唯一齿轮中扩展「模型授权与映射」。账号授权按真实 upstream 独立保存，映射按提供方共享；不新增账号映射表或重复设置入口。
- 复用 `channel_models`、`publicModelId`、`mappingSource`。自动、手工指定、使用上游 ID 三种模式保留明确意图；手工同值仍冻结，既有 `models_dev` 来源不会因同步或无关保存改为自动。
- `V71__provider_model_mapping_state.sql` 新增 `catalog_active` 和渠道独立的 `model_mapping_revision`。这是尚未发布的功能迁移，先后因主线引入 `V69__client_key_access_denylist.sql`、`V70__iq_test_jobs.sql` 重号而由 V69 经 V70 改名至 V71；主线 `V68__client_key_access_scope.sql`、`V69__client_key_access_denylist.sql`、`V70__iq_test_jobs.sql` 和其他历史 SQL 不修改。
- 目录消失的显式映射 inactive 保存，重现恢复；消失的自动项删除。未知目录仅延续原 active 集合；全部目录已知时精确收敛，包括空并集。默认冲突按 `createdAt ASC, id ASC` 选择。
- 提供方目录使用独立完整仓储投影，不受管理列表数量截断影响。上游抓取在事务外，提交目录、settings、旧 provider/models 及删除后重算共用 provider 父行锁和事务边界。
- 只读共享 `model-mappings` API 不请求上游或创建空渠道。原账号 `modelCatalog: string[]` 保持兼容；settings PUT 原子保存账号设置、授权、增量映射，并用 revision 防止静默覆盖。非法修改及 409 整次回滚。
- Canonical 仅作为对外匹配 ID，转发仍使用真实 upstream；inactive 不曝光、不路由、不参与模型测试或协议探测。
- 保留主线调用方的模型、渠道、静态密钥白名单与不可用诊断。当前 public ID、active、调用方范围、账号 upstream 授权共同约束路由；映射变化不自动改写调用方白名单。调用方空名单为零授权，OAuth `models=[]` 仍表示全部。
- 前端复用 SearchSelect 的局部自由输入包装。全部授权不禁用映射；保留目录外授权及草稿，但不创建未知共享映射。同步/保存互斥，捕获弹窗会话并使用不可变保存快照；冲突逐 dirty 行核对，409 不自动重试。保存成功但刷新失败明确提示不要重复提交。

接口与使用说明：[API](api.md#提供方共享的-canonical-映射)、[配置](configuration.md#订阅账号的模型授权与映射)。

## 整合后的验收

在全新、无 `.env*`、旧 target、缓存及备份的源码快照中验证，复制当前源码字节而非旧 Git 基准。重新编译生产与测试代码，定向结束后归档报告，再执行一次完整回归；不把旧 XML 混入本轮结果。PostgreSQL 使用既有 Testcontainers support 和完整 Flyway schema，不降低覆盖来提速。

| 项目 | 当前状态 |
|---|---|
| provider、OAuth HTTP、availability、health、迁移及调用方范围交叉定向测试 | 120/120；fail/error/skip=0；编译与测试退出 0 |
| 完整 upstream / app / extensions | 1,488/1,488（upstream 622 / app 844 / extensions 22）；fail/error/skip=0；各阶段退出 0 |
| 架构与分类门禁 | 34 / 13 项通过，包含于完整回归，不重复计数 |
| 前端 tests / typecheck / 默认 production build | 632/632，90 suites；fail/cancel/skip/todo=0；全部退出 0 |
| 整合影响路径的隔离 GUI | 冲突确认门禁、桌面/手机浅色弹窗、平板与桌面深色、手机深色列表及候选搜索 Escape 焦点返回通过；确认后的提交与手机深色弹窗因滚动工具超时未执行，不计通过 |
| 动态迁移及 shaded JAR 校验 | 71 份资源各一次、字节一致；main 70 份历史 SQL 未改；测试依赖未泄漏 |
| Docker 后端 build 阶段及导出 JAR | build / create / copy / remove 退出 0；容器未启动；同一迁移和依赖校验通过 |
| 独立只读 Standards / Spec | 前两次整合各自独立复核：Standards 硬违规均 0（二次整合 5 条 P3 建议，3 条已修复、2 条记录理由后接受），Spec 遗漏/范围扩张/实现错误均 0；第三次整合为 merge 零文本冲突加迁移改名与分类补登记，未新增生产逻辑 |

第三次整合后的完整回归用例身份与数量一致，120 项定向身份全部包含于 1,488 项完整执行。相对第二次整合的 1,491 项：本功能两条迁移用例因 V71 改名重命名；main 自行以 1 条持久化集成测试替换 4 条内存注册表单测（`IqTestJobRegistryTest` → `IqTestJobPersistenceIntegrationTest`），已在身份审计中逐条归因为主线自身改动，覆盖移除 0、新增 1。前两轮对原基准 a8d4b610 的 3 条 availability 加强重命名与二次整合的审计仍然有效。`BrowserScreenshotRendererTest` 的 11 项实际执行且无跳过。

- 第三次整合后完整回归阶段耗时：compile 42.370s、upstream 51.719s、app 217.657s、extensions 5.470s、summary 0.163s；不把冒烟编译及 focused 重复计入完整回归总数。
- 第三次整合后验证源码指纹（排除文档等非编译输入）：`0a800376738f3a7aaf618fee37eabf2cfde8552b22bfdc5db18c0e7824204e7f`。因验证期间发现另一工作树的并发构建反复覆盖共享 Maven 仓库（同机 `~/.m2`），后端整轮验证改用独立私有仓库副本执行；PR 评审意见修复后重跑前端三件套，本表与指纹对应最终状态。评审修复仅涉及 web 测试 fixture 与其回归，后端源码字节与 1,488 全绿运行完全一致。
- 本地 shaded JAR SHA-256：`4cda18d9a6cb639dd75ce47c386eb793777b72b923a8e261de8b14ad0ad6b974`。
- Docker build 导出 JAR SHA-256：`f7d751d4eb8ef297d66d54324078d09458151f99f14d902d521e262345260361`。分别校验资源，不要求两个不同构建的 JAR 全文件可复现一致。

后端采用 [现行 CI 执行流程](testing.md)，先 `mvn -DskipTests install` 再执行已编译测试。`-DskipTests` 不是测试通过，也不跳过测试编译。核心命令：

```bash
mvn --batch-mode --no-transfer-progress -DskipTests install
mvn --batch-mode --no-transfer-progress -pl prism-app surefire:test \
  -Dtest=ProviderChannelProvisionerTest,SubscriptionOAuthIntegrationTest,ProviderModelAvailabilityTest,ChannelHealthRepositoryTest,OAuthAccountRepositoryTest,OAuthAccountMigrationTest,RequestLogAuditIntegrationTest,ClientKeyRoutingAccessIntegrationTest \
  -DfailIfNoTests=true
# 归档定向报告，重新编译后执行一次全量：
mvn --batch-mode --no-transfer-progress -DskipTests install
mvn --batch-mode --no-transfer-progress \
  -pl prism-test-support,prism-shared,prism-protocol,prism-channel,prism-routing,prism-resilience,prism-subscription-oauth,prism-quota,prism-pricing,prism-request-log,prism-admin \
  surefire:test -DfailIfNoTests=true
mvn --batch-mode --no-transfer-progress -pl prism-app surefire:test -DfailIfNoTests=true
mvn --batch-mode --no-transfer-progress -f extensions/quota-providers/pom.xml verify
npm --prefix web ci --no-audit --no-fund
npm --prefix web test
npm --prefix web run typecheck
npm --prefix web run build
```

## 原基准的历史验证

下列结果只对原 `a8d4b610` 基准有效，保留以区分恢复验证与整合验证：

- 后端最终 1,405 项通过（upstream 613、app 770、extensions 22）；失败、错误、跳过均 0。最终 app/extensions 是审查修复后重跑，upstream 沿用同基准此前成功结果，并非恢复会话重跑全量。
- 原根 reactor 1,358 项身份均保留，缺失 0、新增 25；extensions 22 另计。架构 34 项、分类 13 项包含在总回归中。
- 恢复会话实际执行前端 599/599（87 suites）、独立 typecheck、默认 production build，退出码均 0。默认构建仅有主包超过 500 KB 的警告。
- 原本地及 Docker 后端 build 的 shaded JAR 68 份迁移各一次且字节匹配，历史 67 份 SQL 未改；不含 test-support、Testcontainers 或 JUnit。
- GUI T13–T18 实际检查手机 390×844、平板 1024×768、桌面 1440×900 两主题布局、候选搜索及 Escape 焦点返回，截图与只读 DOM 交叉确认。错误交互沿用此前已确认的验收，不冒称六种尺寸/主题各重跑全部保存流程。
- 原独立 Standards 最终硬违规 0、判断性问题 0；Spec 最终剩余 findings 0。本轮仍需对整合后的差异重新独立复核。

## 第一次整合的历史验证

下列结果只对第一次整合（冻结 main `83f4d5d1…`）有效，保留以区分各次整合：

- 后端定向 111/111、完整 1,469/1,469（upstream 620 / app 827 / extensions 22），fail/error/skip=0；前端 622/622（90 suites）、typecheck、默认 production build 退出 0。
- 两项审查问题在该快照完成真实红绿：前端首次可信 baseline 冲突门禁（50 例 1 fail → 修复后全过）、后端 nullable-default revision（24 例恰 2 fail → 修复后全过）；相关回归已包含于后续各轮结果。
- 验证源码指纹 `84293218e9efbc36018ac1eb3c52c27f98eaa3e6b47b22add425a36ec5246323`；本地 JAR `9243d10d…4244`、Docker JAR `c517d97d…0688`（69 份迁移、68 份历史 SQL）。
- 独立 Standards 硬违规 0、判断性建议 0（P3 重复代码以局部 helper 关闭）；Spec 遗漏/范围扩张/实现错误均 0（两项 P2 关闭）。

## 第二次整合的历史验证

下列结果只对第二次整合（冻结 main `41b799d1…`，迁移名 V70）有效：

- 后端定向 120/120、完整 1,491/1,491（upstream 622 / app 847 / extensions 22），fail/error/skip=0；前端 626/626（90 suites）。
- 验证源码指纹 `45b33a43…064b`；本地 JAR `adc13b4e…b35f`、Docker JAR `c836a7cb…6197`（70 份迁移、69 份历史 SQL）。
- 其 P3 处置与 Spec 结论已在上方边界节合并记录；被第三次整合的 V71 改名与主线新增取代的数字仅作历史对照。

## 失败、警告与边界

- 原实施早期有编译导入、JUnit discovery、fixture 必填列、测试宿主关闭后读数据库及旧 Flyway 版本断言失败，均已修复并实际重跑；0 tests 不算通过。
- 后端 unknown 自动默认缺陷曾以 4 条测试在旧代码复现失败，随后修复；同步 dirty 冲突、模拟器接受未知映射、PUT→GET 期间模型恢复导致草稿丢失的审查问题均已关闭并保留回归。
- 历史桌面 GUI T1 输入/选择器跨列重叠，结束测试后修复，后续布局复验通过。本轮点击、滚动工具多次超时（弹窗纵向与列表横向），均重新观察后以正常可见操作完成或单列受阻；未强制点击或注入页面事件。确认后的提交路径因滚动工具超时未执行，弹窗级 Escape 关闭后焦点落在 body 已如实记录（候选搜索层级的焦点返回契约通过）。浏览器运行时不提供 console 监听或独立 Chrome profile，不宣称这些能力或真实移动设备验收。
- 本轮 Spec 的首次可信 baseline 问题用同一 mounted 文件复现：修复前 50 tests / 49 pass / 1 fail、exit 1；修复后 50/50、exit 0。新增回归检查旧 dirty 草稿、最新共享值、确认门禁及重复 GET 不自动放行，未知映射仍不创建。该红绿在第一次整合快照执行。
- 本轮 nullable-default revision 问题在独立干净快照中只替换为修复前的 provider 生产代码，保留新测试：24 tests 中恰两项失败、error/skip=0、Maven exit 1；修复版完整编排 24/24 并纳入当时 111 定向与 1,469 完整回归。非空默认值变化仍推进手工行 revision。
- 发布核对发现 main 前移且 V69 重号后：功能迁移改名 V70（第三次整合再改 V71），升级路径测试覆盖 V68→V69→V70→V71，迁移用例随之重命名并逐轮纳入一对一身份审计；黑名单 scope 与本功能 `catalogActive`/canonical 组合经重新读取合并结果核对——inactive 仍走 404 语义，黑名单只作用于 active 条目。二次整合首次重跑暴露两条迁移用例在 V68 阶段的 Exposed 读写与主线扩展后的表定义不兼容，重写为与主线黑名单用例一致的原生 JDBC 播种后通过。
- 二次整合 Standards 复核给出 5 条 P3：恒真断言与冗余断言已修复，UpstreamModelCatalog KDoc 补充 inactive 前置条件；fixture 行 ID 前缀与播种风格两条为纯外观/风格判断，记录理由后接受。Spec 复核 0/0/0，并注明 denylist×inactive 组合由 catalogActive 先于 scope 判定的结构保证。
- 三次整合发现主线 `IqTestJobPersistenceIntegrationTest` 带 integration 标签但未登记分类与 shard，完整回归的分类门禁因此失败；按既有 IQ 测试归属补登记 shard-3 并给类补标签（main 自身遗漏，非本功能问题）。同轮完整回归的 app 阶段曾出现缺列与 NoSuchMethodError 混合失败，定位为同机另一工作树的并发 Maven 构建反复覆盖共享 `~/.m2` 构件；改用独立私有仓库副本后全量转绿，该干扰与产品无关。
- PR-Agent 评审指出模拟 fixture 收到任何非空映射增量都会递增版本，与"仅真实变化递增"的版本契约不符（重复保存同值映射会使另一弹窗遭遇 409）。以同文件红绿修复：红 50/51（新回归恰为唯一失败），fixture 改为比对修改前后 mode/canonical/来源并仅在真实变化时递增；绿 51/51，前端全量 632/632，重复提交同值不再推进版本而真实变化仍递增。
- 本轮前端日志保留 HMR 24678 端口占用警告；tests 全部通过。默认 production 主 JS 609.69 kB（gzip 195.39 kB），仍有超过 500 kB 警告。Maven shade 的重复依赖资源及测试宿主的 Micrometer 警告保留，资源唯一性另行实测，不以构建退出 0 隐藏警告。
- 两轴最终复核曾因 provider TLS 连接中断未完成，随后在原代理上下文恢复只读复核；中断不作为通过。仓库未配置技能建议的 issue tracker；本轮复核以用户提供的完整规格和批准计划为依据，未新增外部 issue 或改动技能配置。
- 原始测试日志、XML、截图、浏览器记录、恢复检查点与构建产物仅保留本地，不作为公开附件、不上传外部服务。本文件只发布可复核的范围、命令与结果摘要，不依赖本机绝对路径或 `target` 链接。
- Docker 只验收后端 `build` 阶段；不运行部署容器、不推镜像、不覆盖 `latest`。不验证真实上游、Docker web/runtime 或正式部署。
