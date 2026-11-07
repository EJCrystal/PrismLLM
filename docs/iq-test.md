# 智商检测

管理台的「智商检测」页用两道内置题观察模型表现。模型通过渠道已保存的上游凭据在本地生成 SVG/HTML，
再由可插拔判分器给出结论。题目自带默认提示词，每次运行前可以在页面上修改；实际使用的提示词会随记录保存。
生成请求不经过数据面，因此不写入请求日志，也不消耗调用方密钥额度。

判分器与模型生成完全解耦：每次运行可以单独选择判分器，渠道地址、渠道密钥和调用方密钥不会传给判分器。

## 题目

### 鹈鹕骑行（pelican）

默认提示词要求模型用 SVG 创作一段可独立运行、自动播放并循环的鹈鹕骑自行车动画：必须有真实、连续、
肉眼可见的动态效果（车轮转动、自然的骑行姿态），清楚表现鹈鹕特征和自行车结构；图形直接写在 SVG 标签内，
不依赖外部资源，也不使用 JavaScript 创建图形或返回等待脚本填充的空容器。

最终判定顺序：

1. 人工判定（通过 / 未通过）；
2. 本次运行选中的判分器结果；
3. 没有判分器结果时，退回本地结构检查：结构全部通过为 `passed`，否则为 `review`。

页面同时展示本地结构检查（HTML/SVG/动画/无外部资源）和沙箱预览。预览会注入 CSP 并阻止外部网络请求，
iframe 不启用脚本执行。
结构检查只判断作品是否具备可预览形态，不判断“画得像不像”。

### 糖果测试（candy）

满血站的糖果测试是组合推理题：黑色袋子里有苹果、桃子、西瓜三种口味，圆形和五角星形两种形状；
各格数量为圆形 7/9/8、五角星形 7/6/4。求最少取出多少颗，才能保证同时拥有不同形状的
苹果味和桃子味糖果。

本实现要求模型把答案和推理过程写进一个单文件 HTML。最终判定使用满血官方规则：
**HTML 可见文字中出现独立的 21（或「二十一」）即通过**。

> 注：满血公开页面展示的数量表按组合推理计算为 29，但其判分规则写的是 21；
> 本实现按要求沿用官方 21 规则，不自行改题。

- 只读取 HTML 的可见文字，`<style>` / `<script>` 中的 `21px`、颜色值等不会误判。
- 可见性按 DOM 层叠结果计算：支持内联样式、样式表优先级、`!important`、`visibility` 继承和
  html 根元素隐藏；`@media print` 等当前预览环境不生效的规则不会参与判分。
- 选中的判分器仍会收到糖果题的 HTML，结果作为参考展示；最终通过/未通过仍由 21 规则决定。

### 修改提示词

- 管理台在题目卡片下方提供提示词编辑框，切换题目时载入该题默认提示词；「恢复默认」可撤销本次修改。
- `POST /admin/api/iq-tests` 支持可选的 `prompt` 字段（最长 20000 个字符）；留空或只包含空白时使用内置默认值。
- 每次运行会保存实际使用的提示词，历史详情可展开查看；判分仍按题目类型和所选判分器执行。
- 糖果题的判分固定按「21」执行，修改题目后答案可能不再匹配，页面会给出提示。

单次结果只代表这道题，不代表模型整体能力。

## 判分器

### 内置判分器

| ID | 名称 | 行为 | 是否发送 HTML 到第三方 |
| :--- | :--- | :--- | :--- |
| `local` | 本地结构 + 人工 | 检查 HTML/SVG/动画/外部资源；结构通过后标记为待人工评分 | 否 |
| `manxue` | 满血AI HTML 分类 | 调用 `manxue.ai` 的 `benchmark=html` 分类接口 | 是 |
| `vision` | 无头浏览器截图 + 视觉模型 | 用无头浏览器在动画周期内采样 6 帧并逐项检查，再调用 `IQ_VISION_BASE_URL` 配置的多模态模型评分 | 截图发送到配置的端点 |

`local` 的判定：

- 没有可解析的 HTML/SVG：`failed`；
- 结构检查全部通过：`review`，等待人工评分；
- 结构检查未全部通过：`incorrect`；
- 糖果题：`review`「由 21 规则判定」，不参与结构判分。

`manxue` 的判定：`normal → passed`、`degraded → incorrect`、`unknown → review`。

`vision` 的判定：

- 用无头 Chrome/Chromium 把模型输出中的第一段内联 SVG 在动画周期内均匀采样 6 帧，逐帧截图为 PNG；
  周期从 SMIL `dur` 或 CSS `animation-duration` 估算，未声明时按 2 秒处理。每个时间点都会：
  - 用 Web Animations API 暂停并把所有 CSS 动画的 `currentTime` 定位到目标时刻；
  - 调用 SVG 的 `pauseAnimations()` / `setCurrentTime()` 推进 SMIL 时间线；
  - 再截取固定尺寸的画面。因此 CSS `@keyframes` 与 SMIL 都能被真实采样，不执行模型提供的脚本，
    也不加载外部资源；
- 六张截图按时间顺序交给多模态模型，要求逐项检查五个维度并分别返回 `passed` / `failed` / `unknown`
  与中文描述：
  1. **鹈鹕特征**：同一只白色长颈鸟，头颈与躯干连贯，长宽喙、橙色喉囊、冠羽和眼睛清楚；
  2. **自行车结构**：前后两个带辐条的轮子，连续双三角车架，车座、前叉、车把、链条和曲柄位置合理；
  3. **骑行关系**：鹈鹕坐在车座上，双腿连续到脚并落在踏板，翅膀末端接触车把握把；
  4. **动画连贯性**：逐帧比较轮辐、腿脚、曲柄和踏板位置，确认存在连续运动且脚未脱离踏板；
  5. **画面完成度**：主体完整居中，轮廓清楚，无裁切、漂浮、错误重叠、缺失、错位或结构碰撞。
- 任一项 `failed` → `incorrect`（疑似降智）；没有失败项但有 `unknown` → `review`；五项全部通过 → `passed`。
  逐项结果与顶层 `verdict` 冲突时以逐项结果为准，失败项优先；
  模型未返回 `aspects` 时五项均按 `unknown` 处理，不能只凭自报的 `verdict` 或分数通过；
- **动画连贯性不再停留在 `unknown`**：六帧完全相同说明没有可见动态效果，直接判 `failed`；
  模型自己返回 `unknown` 时也按 `failed` 处理。其他维度仍可 `unknown` 并进入人工确认；
- 明确失败条件：部件漂浮、错误重叠、缺失、错位、画面截断；鹈鹕没有坐在车座上；脚没有在踏板上；
  翅膀没有握住车把；自行车结构不合理；缺少两个轮子、车架、车把、踏板或链条；
- 找不到内联 SVG、渲染失败或模型调用失败时返回 `review`，不会把基础设施故障算成模型失败；
- 视觉判分只使用无头 Chrome/Chromium；未找到浏览器时直接返回 `review`（需人工查看），
  不会降级到其他渲染器，也不会阻止网关启动；
- 浏览器进程隔离：网关以非 root 运行时保留 Chrome 自己的沙箱；以 root 运行时先用
  `setpriv` 把浏览器降到 `nobody`，再关闭在 root 下无法启动的沙箱，并清空进程环境，
  避免浏览器被恶意作品攻破后拿到网关权限、数据库地址或判分密钥。root 下找不到
  `setpriv` 时不会退回 root 无沙箱运行，而是返回 `review`（需人工查看）；
- Docker x86_64 镜像已经预装 Chrome；其他架构或本机运行时可通过 `IQ_VISION_BROWSER_PATH` 指定浏览器；
- 只渲染第一段内联 SVG；完全用 HTML/CSS 绘制、没有内联 SVG 的输出仍会返回 `review`；
- 糖果题不参与视觉判分，仍由 21 规则判定。

启用 `vision` 需要设置 `IQ_VISION_ENABLED=true`，并配置 `IQ_VISION_BASE_URL` 与 `IQ_VISION_MODEL`。
端点使用 OpenAI 兼容的 `/chat/completions` 协议；`IQ_VISION_BASE_URL` 可以直接指向本机 PrismLLM 网关，
这样截图不会离开自己的部署。

### 外部 JAR 判分器

外部判分器通过稳定的 `IqJudge` SPI 接入，按现有额度插件的方式从 `IQ_JUDGE_PLUGIN_DIR`
加载 JAR，并使用 `META-INF/services/dev.soven.llmgateway.spi.iq.IqJudge` 注册。
编译方式、示例代码与安全边界见 [iq-judge.md](plugins/iq-judge.md)。

判分器 ID 只允许小写字母、数字、`.`、`_` 和 `-`，最长 64 个字符；内置判分器优先，
重复 ID、非法 ID 或不支持的 SPI 版本会在判分器列表中显示为加载失败，不影响其他判分器。

### 隐私

- `local` 判分不出本机。
- 选择 `manxue` 时，模型生成的 HTML 会发送到 `manxue.ai`；渠道地址、渠道密钥和调用方密钥不会发送。
- 选择 `vision` 时，只会把渲染出的 6 帧 PNG 截图发送到 `IQ_VISION_BASE_URL`，不发送原始 HTML 或渠道信息。
  `IQ_VISION_API_KEY` 只保存在网关进程内，不写入数据库，也不会回显到管理台。端点指向本机网关时截图不出部署。
- 外部 JAR 判分器属于受信代码，会收到 HTML 与本地结构摘要；部署前应自行审查插件内容。
- 如果部署环境不允许访问第三方服务，设置 `MANXUE_ENABLED=false`，`manxue` 就不会出现在判分器列表中；
  `vision` 默认关闭，不配置 `IQ_VISION_ENABLED=true` 就不会注册。

## 满血 API 接入

公开接口不需要注册或本站令牌：

```bash
curl -X POST https://manxue.ai/api/v1/tests \
  -H 'Content-Type: application/json' \
  -d '{
    "benchmark": "html",
    "base_url": "https://api.example.com/v1",
    "api_key": "not-used",
    "model": "gpt-6-astra",
    "html": "<!DOCTYPE html><html>...</html>"
  }'
```

返回 `202` 和任务 `id`，随后轮询：

```bash
curl https://manxue.ai/api/v1/tests/<id>
```

完成后返回 `assessment.quality` / `label` / `reason` / `source`。`html` 模式只在满血服务端做本地分类，
不访问上游，也不使用 `api_key`；`base_url` 与 `api_key` 只是协议必填字段，`model` 必须填 `gpt-6-astra`。

### 配置

| 变量 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `IQ_DEFAULT_JUDGE` | `local` | 默认判分器 ID；不存在时退回 `local`，再退回第一个可用判分器。 |
| `IQ_JUDGE_TIMEOUT_MS` | `60000` | 单次判分的总超时，1000–300000；启用 `vision` 且未显式配置时自动提高到 6×浏览器单帧超时+视觉超时（最多 300000）。 |
| `IQ_JUDGE_PLUGIN_DIR` | `plugins/iq-judges` | 外部判分器 JAR 目录；不存在时视为空目录。 |
| `MANXUE_ENABLED` | `true` | 是否注册 `manxue` 判分器。 |
| `MANXUE_API_BASE_URL` | `https://manxue.ai` | 满血 API 基础地址。 |
| `MANXUE_TIMEOUT_MS` | `60000` | POST + 轮询的总超时。 |
| `IQ_VISION_ENABLED` | `false` | 是否注册内置 `vision` 判分器。 |
| `IQ_VISION_BASE_URL` | 空 | OpenAI 兼容端点的基础地址；启用时必填，需以 `http://` 或 `https://` 开头。 |
| `IQ_VISION_API_KEY` | 空 | 可选的 Bearer 令牌，只保存在网关进程内。 |
| `IQ_VISION_MODEL` | 空 | 多模态模型名；启用时必填。 |
| `IQ_VISION_TIMEOUT_MS` | `60000` | 单次视觉评分超时，1000–300000。 |
| `IQ_VISION_RENDER_WIDTH` | `1024` | 单帧截图宽度上限，256–2048；多帧判分时单帧最大 640。 |
| `IQ_VISION_MAX_IMAGE_BYTES` | `4000000` | 单帧截图大小上限，100000–20000000；超过时返回 `review`。 |
| `IQ_VISION_BROWSER_PATH` | 空 | Chrome/Chromium 可执行文件或 PATH 命令名；为空时自动探测。 |
| `IQ_VISION_BROWSER_TIMEOUT_MS` | `15000` | 单帧浏览器渲染超时，1000–60000。 |

## 管理面 API

所有接口要求管理员会话 Cookie；`POST` / `PUT` 还需要 `X-CSRF-Token`。

### 题目定义

```bash
curl -b cookies.txt http://localhost:8080/admin/api/iq-tests/benchmarks
```

返回 `id`、`label`、`summary`、`prompt` 与 `grading`。

### 判分器列表

```bash
curl -b cookies.txt http://localhost:8080/admin/api/iq-tests/judges
```

返回 `judges`（`id`、`label`、`description`、`builtIn`、`apiVersion`）、
`defaultJudgeId` 与 `failures`（插件加载失败诊断）。

### 可测试目标

```bash
curl -b cookies.txt http://localhost:8080/admin/api/iq-tests/targets
```

按「渠道 × 已授权模型」返回可用凭据与协议。没有可路由凭据的组合不会出现。

### 运行检测

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/iq-tests \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"channelId":"<channel-id>","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"local"}'
```

`benchmark` 取 `pelican` 或 `candy`。`keyId`、`protocol` 可省略；省略协议时优先 Responses，
其次 Chat Completions。`judgeId` 省略时使用 `IQ_DEFAULT_JUDGE`。生成请求同步等待上游完成：
图形题超时 180 秒，糖果题超时 120 秒；判分另有 `IQ_JUDGE_TIMEOUT_MS` 超时。

响应包含 `verdict`（`passed` / `incorrect` / `review` / `failed`）、`checks`、`html`、
`responseText`、`judgeResult`（`judgeId` / `judgeLabel` / `verdict` / `label` / `reason` / `score` / `details`）、
`manualVerdict`、`usage` 与上游声明的模型名。

### 人工判定

```bash
curl -b cookies.txt -X PUT http://localhost:8080/admin/api/iq-tests/<id>/manual-verdict \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"verdict":"passed","score":5,"note":"鹈鹕和自行车都能辨认"}'
```

只适用于鹈鹕题，且记录中必须存在可解析的 HTML。`verdict` 取 `passed` 或 `incorrect`，
`score` 为 1–5 或空，`note` 最长 500 个字符。人工判定会覆盖最终 `verdict`，自动判分结果仍保留在
`judgeResult` 中。

### 历史与详情

```bash
curl -b cookies.txt 'http://localhost:8080/admin/api/iq-tests?limit=50'
curl -b cookies.txt http://localhost:8080/admin/api/iq-tests/<id>
```

历史上限由服务端固定为最近 200 次；列表只返回摘要，详情返回完整响应与结构检查。
