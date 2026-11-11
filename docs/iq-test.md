# 智商检测

管理台渠道列表的「测试 → 智商检测」页签用鹈鹕骑行动画和随机推理题观察模型表现。模型通过渠道已保存的上游凭据请求上游：
鹈鹕题生成 SVG/HTML，糖果题从题库随机抽取，只返回 JSON。鹈鹕提示词可在运行前修改，糖果题通过题库编辑。
实际提示词和判分答案快照会随记录保存。生成请求不经过数据面，因此不写入请求日志，也不消耗调用方密钥额度。

判分器与模型生成完全解耦：鹈鹕题每次运行可以单独选择判分器，渠道地址、渠道密钥和调用方密钥不会传给判分器；
糖果题只读取 JSON 答案，不调用判分器。

## 题目

### 鹈鹕骑行（pelican）

默认提示词要求模型用 SVG 创作一段可独立运行、自动播放并循环的鹈鹕骑自行车动画：必须有真实、连续、
肉眼可见的动态效果（车轮转动、自然的骑行姿态），清楚表现鹈鹕特征和自行车结构；图形直接写在 SVG 标签内，
不依赖外部资源，也不使用 JavaScript 创建图形或返回等待脚本填充的空容器。

最终判定顺序：

1. 人工判定（通过 / 未通过）；
2. 本次运行选中的判分器结果；
3. 没有判分器结果时，退回本地结构检查：参与判定的结构项全部通过为 `passed`，否则为 `review`。

页面同时展示本地结构检查（HTML/SVG/动画/无外部资源/单场景/图层顺序）和沙箱预览。预览会注入 CSP
并阻止外部网络请求，iframe 不启用脚本执行。
结构检查只判断作品是否具备可预览形态，不判断“画得像不像”。

结构检查的判据落在结果上，不限定实现手法（SMIL、CSS 都可以）：

| 检查 | 判定 |
| --- | --- |
| 可解析为 HTML/SVG、包含内联 SVG | 必备项，缺失直接 `failed` |
| 包含循环动画、不依赖外部资源 | 必备项，缺失为 `review` |
| 场景绘制在同一 SVG 内 | 顶层 `<svg>` 多于一个，或用带背景命名的 HTML 块（如 `div.hill`）拼背景时否决；只包着 SVG、也没自己画背景的布局容器放行（内联 `style` 与文档 `<style>` 里真正选中该元素、且值不为 `none`/`transparent` 的背景声明都算画了背景）；`<foreignObject>` 里的 HTML 内容仍在场景内 |
| 远侧腿画在自行车之前 / 近侧腿画在鹈鹕之后 | 只在 id/class/`data-layer` 等标注能辨认出两侧时比较绘制顺序，排反则否决；标注须同时含方位词和腿脚词（`back-wheel` 不算腿） |

图层顺序只在能离线确认时判定：远侧比最靠后的远侧图元与最靠前的自行车图元，近侧比最靠前的近侧图元与最靠后的鹈鹕图元；
`<defs>`/`<symbol>` 等只存定义、不直接绘制的内容不参与比较。没打可辨认标记、或两侧图元互为嵌套容器的作品记为「提示」项
（`decisive=false`），只展示、不否决，跨骑遮挡交给视觉判分或人工确认。

### 糖果测试题库（candy）

在「糖果测试 → 管理题库」中新增、编辑、启停、搜索或删除题目。每题包含标题、题干、答案类型（数字或文本）、标准答案和启用状态。V62 迁移加入原糖果题并保留答案 21，之后可自行修改。题干最长 20000 字符，标准答案最长 2000 字符。

新管理台传入 `useQuestionBank=true`，由服务端从全部启用题目中等概率抽一道，允许重复；空题库或全部停用时不会调用上游。该模式不能同时提交 `prompt`，也不能用于鹈鹕题。模型仅收到题干、禁止联网的要求和通用 JSON 输出要求，不收到标准答案、题目标题或其他题目。录入时不要把标准答案写进题干。

- 数字题：读取 JSON 的 `answer`，数字与数字字符串按十进制值比较，支持小数和科学计数法，不作浮点近似比较。
- 文本题：`answer` 必须为非空 JSON 字符串；仅去除首尾空白，区分大小写，不作语义评分。
- 答案相同为 `passed`，不同为 `incorrect`；JSON 无效、答案缺失、空答案或类型不符为 `failed`。
- `reason` 可选，不参与判分；兼容 JSON 代码围栏及前后少量说明文字。
- 历史保存题目 ID、标题、实际提示词、答案类型、标准答案和实际答案快照。编辑、停用或删除题目后，旧记录仍使用原快照判分。

糖果测试不声明或执行工具，也不调用判分器。全局及渠道的请求体覆盖仅允许调整采样、输出预算和推理设置，不能注入工具、搜索、旧对话或替换题干；请求头与鉴权仍按渠道配置处理。应用不发起联网搜索，但无法审计第三方上游服务内部是否自行联网。

### 原糖果题与旧接口兼容

未传 `useQuestionBank` 或传 `false` 的旧 API 调用继续使用以下固定题目及整数答案规则。

满血站的糖果测试是组合推理题：黑色袋子里有苹果、桃子、西瓜三种口味，圆形和五角星形两种形状；
各格数量为圆形 7/9/8、五角星形 7/6/4。求最少取出多少颗，才能保证同时拥有不同形状的
苹果味和桃子味糖果。

本实现要求模型只返回一个 JSON 对象，不输出 HTML：

```json
{"answer": 21, "reason": "简要推导过程"}
```

最终判定在本地完成，使用满血官方答案 **21**：

- `answer` 等于 21：`passed`；
- `answer` 是其他整数：`incorrect`；
- 返回内容无法解析为 JSON 对象，或 `answer` 缺失/不是整数：`failed`；
- 允许 Markdown 代码围栏或 JSON 前后有少量说明；会依次尝试每个 `{` 起点，取第一个能解析为 JSON 对象的片段（说明文字里的花括号不会挡住答案，最多尝试 20 个起点）；
- 只读取 `answer`，不评价 `reason` 的推理质量，也不调用任何 HTML 判分器；
- 升级前生成的糖果记录保留当时的 HTML 判定和预览，不按新规则重新计算。

> 注：满血公开页面展示的数量表按组合推理计算为 29，但其判分规则写的是 21；
> 本实现按要求沿用官方 21 规则，不自行改题。

### 修改提示词与旧接口兼容

- 管理台在鹈鹕题目卡片下方提供提示词编辑框；「恢复默认」可撤销本次修改。糖果题通过「管理题库」编辑。
- `POST /admin/api/iq-tests` 支持可选的 `prompt` 字段（最长 20000 个字符）；留空或只包含空白时使用内置默认值。
- 每次运行会保存实际使用的提示词，历史详情可展开查看；鹈鹕题按所选判分器执行，糖果题按 JSON 的 `answer` 判定。
- 旧 API 自定义糖果提示词仍按整数 21 判分；新管理台按随机抽中题目的答案快照判分。

单次结果只代表这道题，不代表模型整体能力。

## 判分器

判分器只用于鹈鹕题。糖果题在本地读取 JSON 的 `answer`，不会调用下列任何判分器，也不会把模型输出发送给它们。

### 内置判分器

| ID | 名称 | 行为 | 是否发送 HTML 到第三方 |
| :--- | :--- | :--- | :--- |
| `local` | 本地结构 + 人工 | 检查 HTML/SVG/动画/外部资源、单场景与图层顺序；结构通过后标记为待人工评分 | 否 |
| `manxue` | 满血AI HTML 分类 | 调用 `manxue.ai` 的 `benchmark=html` 分类接口 | 是 |
| `vision` | 无头浏览器截图 + 视觉模型 | 用无头浏览器在动画周期内采样 6 帧并逐项检查，再调用 `IQ_VISION_BASE_URL` 配置的多模态模型评分 | 截图发送到配置的端点 |

`local` 的判定：

- 没有可解析的 HTML/SVG：`failed`；
- 参与判定的结构检查全部通过：`review`，等待人工评分；
- 有参与判定的结构检查未通过：`incorrect`。

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
  3. **骑行关系**：鹈鹕坐在车座上，双腿连续到脚并落在踏板，翅膀末端接触车把握把，踏板面保持水平；
  4. **动画连贯性**：逐帧比较轮辐、腿脚、曲柄和踏板位置，确认存在连续运动，脚未脱离踏板，
     脚踏点没有偏离曲柄圆周，身体起伏时大腿根部没有与躯干脱节；
  5. **画面完成度**：主体完整居中，轮廓清楚，无裁切、漂浮、错误重叠、缺失、错位或结构碰撞，
     跨骑透视正确（远侧肢体被车架局部遮挡属正常，远近关系画反或近侧大腿根部被躯干遮住才算错）。
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
  输出目录交给 `nobody` 独占，清理时按目录句柄逐项删除且不跟随符号链接；
- Docker x86_64 镜像已经预装 Chrome；其他架构或本机运行时可通过 `IQ_VISION_BROWSER_PATH` 指定浏览器；
- 只渲染第一段内联 SVG；完全用 HTML/CSS 绘制、没有内联 SVG 的输出仍会返回 `review`。

`vision` 判分器始终注册，是否真正调用模型由「系统配置 → 智商检测视觉判分」里的开关决定；
未保存过页面配置时使用 `IQ_VISION_ENABLED` / `IQ_VISION_BASE_URL` / `IQ_VISION_MODEL` 等环境变量默认值。
端点使用 OpenAI 兼容的 `/chat/completions` 协议；`IQ_VISION_BASE_URL` 可以直接指向本机 PrismLLM 网关，
这样截图不会离开自己的部署。

页面配置保存后立即生效，不需要重启网关。可热切换的是启用开关、模型端点、模型名、API 密钥和视觉模型超时；
渲染宽度、单帧浏览器超时、浏览器路径属于进程级绑定，仍需通过环境变量调整并重启。

### 外部 JAR 判分器

外部判分器通过稳定的 `IqJudge` SPI 接入，按现有额度插件的方式从 `IQ_JUDGE_PLUGIN_DIR`
加载 JAR，并使用 `META-INF/services/dev.soven.prismllm.spi.iq.IqJudge` 注册。
编译方式、示例代码与安全边界见 [iq-judge.md](plugins/iq-judge.md)。

判分器 ID 只允许小写字母、数字、`.`、`_` 和 `-`，最长 64 个字符；内置判分器优先，
重复 ID、非法 ID 或不支持的 SPI 版本会在判分器列表中显示为加载失败，不影响其他判分器。

### 隐私

- `local` 判分不出本机。
- 选择 `manxue` 时，鹈鹕题生成的 HTML 会发送到 `manxue.ai`；糖果题不调用判分器，不会发送任何输出。
  渠道地址、渠道密钥和调用方密钥不会发送。
- 选择 `vision` 时，只会把渲染出的 6 帧 PNG 截图发送到当前生效的视觉模型端点，不发送原始 HTML 或渠道信息。
  端点指向本机网关时截图不出部署。
- 视觉模型 API 密钥不会回显到管理台。页面保存的密钥与渠道凭据一样加密存储；环境变量里的
  `IQ_VISION_API_KEY` 只存在于网关进程内。密钥与端点绑定：页面端点与环境变量端点一致时留空会沿用环境变量密钥，
  端点被改成别的服务后必须重新填写或明确清除，否则保存会被拒绝。
- 保存过的密钥无法解密（主密钥轮换或密文损坏）时**不会**回退到环境变量密钥，接口返回
  `apiKeyUnreadable`，系统配置卡片与弹窗都会提示重新填写。静默换成另一个账号的凭据只会在 401 时暴露。
- 外部 JAR 判分器属于受信代码，会收到 HTML 与本地结构摘要；部署前应自行审查插件内容。
- 如果部署环境不允许访问第三方服务，设置 `MANXUE_ENABLED=false`，`manxue` 就不会出现在判分器列表中；
  `vision` 判分器始终在列表里，关闭页面配置里的开关（或保持环境变量 `IQ_VISION_ENABLED=false`）即可不调用任何外部模型。

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
| `IQ_VISION_ENABLED` | `false` | 未保存页面配置时，视觉判分是否启用。 |
| `IQ_VISION_BASE_URL` | 空 | OpenAI 兼容端点的基础地址；启用时必填，需以 `http://` 或 `https://` 开头。 |
| `IQ_VISION_API_KEY` | 空 | 可选的 Bearer 令牌，只存在于网关进程内；端点与之一致时供页面配置回退使用。 |
| `IQ_VISION_MODEL` | 空 | 多模态模型名；启用时必填。 |
| `IQ_VISION_TIMEOUT_MS` | `60000` | 单次视觉评分超时，1000–300000。 |
| `IQ_VISION_RENDER_WIDTH` | `1024` | 单帧截图宽度上限，256–2048；多帧判分时单帧最大 640。 |
| `IQ_VISION_MAX_IMAGE_BYTES` | `4000000` | 单帧截图大小上限，100000–20000000；超过时返回 `review`。 |
| `IQ_VISION_BROWSER_PATH` | 空 | Chrome/Chromium 可执行文件或 PATH 命令名；为空时自动探测。 |
| `IQ_VISION_BROWSER_TIMEOUT_MS` | `15000` | 单帧浏览器渲染超时，1000–60000。 |

## 管理面 API

所有接口要求管理员会话 Cookie；`POST` / `PUT` 还需要 `X-CSRF-Token`。

### 题库管理

接口均要求管理员会话；写操作要求 `X-CSRF-Token` 并记录审计事件，不记录题干或标准答案。

- `GET /admin/api/iq-tests/questions?limit=200&offset=0`：按 ID 倒序返回题目，limit 为 1–200，offset 为非负整数。
- `POST /admin/api/iq-tests/questions`：新增，返回 201。
- `PUT /admin/api/iq-tests/questions/{id}`：完整更新题目。
- `DELETE /admin/api/iq-tests/questions/{id}`：删除，返回 204；不影响历史。

新增与修改字段：`title`、`prompt`、`answerType`（`number` / `text`）、`expectedAnswer`（字符串）、`enabled`（默认 true）。数字按精确十进制规范化，指数范围受限；文本去除首尾空白。

运行请求增加可选 `useQuestionBank`（默认 false）。题库运行响应额外包含 `questionId`、`questionTitle`、`answerType`、`expectedAnswerValue`、`actualAnswer`；完整答案仅出现在详情。原 `expectedAnswer` / `matchedAnswer` 整数字段继续保留，能表示整数的数字题会填充。

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

### 视觉判分配置

```bash
curl -b cookies.txt http://localhost:8080/admin/api/iq-vision-settings
```

返回当前生效的 `enabled`、`baseUrl`、`model`、`timeoutMs`，以及 `apiKeySet` / `apiKeySource`
（`settings`、`environment`、`none`）、`source`（配置来自页面还是环境变量）、`browserAvailable`。
另外附带 `browserTimeoutMs` 与 `judgeTimeoutMs`，用于判断「6×单帧渲染 + 视觉调用」是否超过判分总超时
（`worstCaseBudgetMs`）。响应从不包含密钥明文。

```bash
curl -b cookies.txt -X PUT http://localhost:8080/admin/api/iq-vision-settings \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"enabled":true,"baseUrl":"http://127.0.0.1:8080/v1","model":"gpt-6.1-sol","apiKey":"...","timeoutMs":60000}'
```

`apiKey` 留空表示沿用已保存的密钥；`clearApiKey=true` 表示显式清除。端点变化时如果不填新密钥也不勾选清除，
接口返回 `400`，避免把旧服务的凭据发给新端点。端点必须是带主机名的 `http://` / `https://` 地址，
显式端口需在 1..65535 之间；不合法时同样返回 `400`。保存后立即生效。

读取配置本身失败时接口返回 `503`，而不是假装「从未保存过配置」；判分侧遇到同样的读库失败会直接
转人工确认，不会回退到环境变量里的端点和启用状态。

### 运行检测

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/iq-tests \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"channelId":"<channel-id>","model":"gpt-4o-mini","benchmark":"pelican","judgeId":"local"}'
```

`benchmark` 取 `pelican` 或 `candy`。`keyId`、`protocol` 可省略；省略协议时优先 Responses，
其次 Chat Completions。鹈鹕题的 `judgeId` 省略时使用 `IQ_DEFAULT_JUDGE`；糖果题忽略 `judgeId`，
也不会调用判分器。生成请求同步等待上游完成：图形题超时 180 秒，糖果题超时 120 秒；
鹈鹕判分另有 `IQ_JUDGE_TIMEOUT_MS` 超时。

响应包含 `verdict`（`passed` / `incorrect` / `review` / `failed`）、`checks`、`html`、
`responseText`、`judgeResult`（`judgeId` / `judgeLabel` / `verdict` / `label` / `reason` / `score` / `details`）、
`manualVerdict`、`usage` 与上游声明的模型名。新运行的糖果题 `html` 与 `judgeResult` 为 `null`；
升级前的糖果记录保留当时的 HTML 与判分器结果。

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
curl -b cookies.txt 'http://localhost:8080/admin/api/iq-tests?limit=200'
curl -b cookies.txt http://localhost:8080/admin/api/iq-tests/<id>
```

历史上限由服务端固定为最近 200 次；列表只返回摘要，详情返回完整响应与结构检查。管理台的
「检测记录」页会一次读取全部保留记录，并在前端按类型、渠道、模型和结论筛选。
