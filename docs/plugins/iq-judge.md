# 智商检测判分器插件

智商检测的判分器是可选的。网关核心提供 `IqJudge` SPI、注册表、有界执行器和人工判定面板；
内置 `local`（本地结构检查 + 人工评分）、`manxue`（满血AI HTML 分类）与 `vision`
（本地无头浏览器逐帧截图 + 配置的多模态模型评分），其余判分能力由独立插件实现。

判分器只接收模型生成的 HTML、题目 ID、模型名和本地结构摘要，不接收渠道地址、渠道密钥或调用方密钥。
外部 JAR 与网关运行在同一进程，属于受信代码，不是沙箱。

## 安装外部插件

需要 JDK 21 与 Maven。在仓库根目录构建核心的 SPI 制品，再构建插件：

~~~bash
mvn -DskipTests install
mkdir -p plugins/iq-judges
cp your-judge/target/your-judge.jar plugins/iq-judges/
~~~

`mvn install` 会同时生成 `prism-shared-<version>-iq-judge-spi.jar`，供插件编译时使用。
插件目录由 `IQ_JUDGE_PLUGIN_DIR` 指定，默认是网关工作目录下的 `plugins/iq-judges`。
Compose 将宿主机 `./plugins` 只读挂载到 `/app/plugins`；若改目录，要同步修改容器环境变量和卷挂载目标。

插件只在启动时加载。复制 JAR 后执行 `docker compose restart gateway`；
本地运行 JAR 时重新启动网关进程即可。删除 JAR 并重启可卸载。

目录按文件名排序发现，同 ID 只保留首个合法实现；非法 ID、不支持的 SPI 版本、重复 ID、
损坏的服务声明会记录为加载失败，健康插件仍可加载。管理台「智商检测」页的判分器下拉会显示
内置与插件判分器，并折叠展示加载失败原因。

## 开发自定义插件

使用 Java、Kotlin 或其他 JVM 语言实现 `dev.soven.llmgateway.spi.iq.IqJudge`，提供公开无参构造函数。
核心发布的轻量编译制品为：

~~~xml
<dependency>
  <groupId>dev.soven</groupId>
  <artifactId>prism-shared</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <classifier>iq-judge-spi</classifier>
  <scope>provided</scope>
  <exclusions>
    <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
  </exclusions>
</dependency>
~~~

Gradle 使用同坐标的 `compileOnly` 依赖，并排除传递依赖。不要将 SPI、Kotlin 标准库或网关核心类
重复打包到插件中。网关使用父优先类加载；额外私有依赖应一并打包，必要时重定位以避免与其他插件冲突。

最小实现：

~~~kotlin
package example

import dev.soven.llmgateway.spi.iq.*

class ExampleJudge : IqJudge {
    override val id = "example"
    override val displayName = "Example 判分器"
    override val description = "把 HTML 交给自建视觉模型评分。"

    override fun judge(request: IqJudgeRequest): IqJudgeResult {
        // request.html 是模型生成的单文件 HTML；request.structure 是本地结构摘要。
        // 在此调用你自己的服务，然后返回 0–100 分或通过/未通过。
        return IqJudgeResult(
            verdict = IqJudgeVerdict.REVIEW,
            label = "待人工确认",
            reason = "示例插件未接入实际判分服务。",
            score = null,
        )
    }
}
~~~

在 `src/main/resources/META-INF/services/dev.soven.llmgateway.spi.iq.IqJudge` 文件中写入：

~~~text
example.ExampleJudge
~~~

ID 必须匹配 `[a-z0-9][a-z0-9._-]{0,63}`，且唯一。`displayName` 为不超过 120 字符的非空名称，
不能含控制字符。当前 `apiVersion` 为 1。参考官方额度插件的独立 `pom.xml` 构建 JAR，
再按上述目录加载，不需要修改核心代码。

### 请求

`IqJudgeRequest` 包含：

| 字段 | 说明 |
| --- | --- |
| `benchmark` | `pelican`；糖果题在本地读取 JSON，不会调用判分器 |
| `html` | 从模型输出中提取的 HTML；纯 SVG 会自动包装成单文件 HTML，没有可解析内容时不会调用判分器 |
| `model` | 被测模型名 |
| `structure` | 本地结构摘要：各项检查、HTML/SVG/动画/无外部资源标记 |

### 结果

`IqJudgeResult` 包含：

| 字段 | 说明 |
| --- | --- |
| `verdict` | `PASSED` / `INCORRECT` / `REVIEW` / `FAILED` |
| `label` | 展示用短标签，最多 120 字符 |
| `reason` | 可选原因，最多 500 字符 |
| `score` | 0–100，可空；超出范围会被判为失败 |
| `details` | 最多 32 项，每项 key ≤ 64、value ≤ 200 字符 |

判分器结果参与鹈鹕题的最终判定；糖果题只读取 JSON 的 `answer`，不会调用判分器。
`FAILED` 表示判分器无法给出结论，不会让作品自动通过。

### 执行与安全

- 判分在专用的最多 4 线程执行器中运行，单次总超时由 `IQ_JUDGE_TIMEOUT_MS` 控制，默认 60 秒。
- 插件必须线程安全、响应线程中断，并自行限制网络请求与响应大小；不要启动长期后台任务。
- 网关关闭时会调用 `close()`；插件异常会被转换为 `FAILED`，不影响网关进程。
- 插件会收到完整 HTML。不要记录、缓存或持久化 HTML；需要截图或调用外部视觉模型时，
  由插件自行负责隐私与合规。
- 这是可信 JVM 插件机制，具有与网关进程相同的系统权限，不是沙箱。只安装可信来源的 JAR。
