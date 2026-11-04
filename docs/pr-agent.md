# PR-Agent 代码审查

仓库使用 PR-Agent（https://github.com/The-PR-Agent/pr-agent）做自动代码审查，模型通过自建 PrismLLM 调用。

- 工作流：`.github/workflows/pr-agent.yml`
- 审查规则：`.pr_agent.toml`
- 请求发往自建网关的 OpenAI 兼容入口（`/v1/chat/completions`），用调用方密钥鉴权
- 主模型和备用模型通过 PR-Agent 的 fallback 配置依次调用网关

## 必须做的配置

### 1. Secret：网关调用方密钥

`Settings -> Secrets and variables -> Actions -> Secrets -> New repository secret`：

```text
LLM_GATEWAY_KEY = <网关管理界面创建的调用方密钥>
```

用命令行设置，值不经过聊天记录：

```bash
cd /path/to/PrismLLM
gh secret set LLM_GATEWAY_KEY
```

这个密钥在管理界面「调用方密钥」里创建，明文只显示一次。

### 2. Variable：网关地址

`Settings -> Secrets and variables -> Actions -> Variables`：

```text
LLM_GATEWAY_BASE_URL = https://gateway.internal.example.com/v1
```

走命令行：

```bash
gh variable set LLM_GATEWAY_BASE_URL --body "https://gateway.internal.example.com/v1"
```

地址必须**包含 `/v1`**，与 README 里渠道 `baseUrl` 的约定一致；PR-Agent 自己追加 `/chat/completions`，不会补 `/v1`。

## 模型顺序、流式请求与超时

工作流将请求发往同一个 OpenAI 兼容网关，并按顺序配置三个模型：

    OPENAI__KEY: <LLM_GATEWAY_KEY secret>
    OPENAI__API_BASE: <LLM_GATEWAY_BASE_URL variable>
    CONFIG__MODEL: openai/gpt-6-luna
    CONFIG__FALLBACK_MODELS: '["openai/deepseek-flash", "openai/gemini-3.8-flash"]'
    CONFIG__REASONING_EFFORT: "low"
    CONFIG__ADDITIONAL_REASONING_EFFORT_MODELS: '["gpt-6-luna", "deepseek-flash", "gemini-3.8-flash"]'
    CONFIG__RETRY_SAME_MODEL_ON_TIMEOUT: "false"
    LITELLM__FORCE_STREAMING_CUSTOM_LLM_PROVIDER: "openai"
    LITELLM__FORCE_STREAMING_API_BASE_SUBSTRINGS: '["/v1"]'

openai/ 前缀让 LiteLLM 走 OpenAI 兼容路径。三个模型都必须能通过网关访问（可在 GET /v1/models 确认）；若网关中使用的是别名，请把配置里的模型 ID 换成对应别名。PR-Agent 把流完整收集后才生成审查结果；启用流式是为了让网关能在首 token 超时时切换候选。

顺序是 gpt-6-luna → deepseek-flash → gemini-3.8-flash，推理强度均为 low。PR-Agent 超时后进入下一模型，不重试刚超时的模型。网关运行环境需设置 FIRST_TOKEN_TIMEOUT_MS=120000，限制单次流式调用等待首 token 的时间。模型回退列表由 PR-Agent 依次尝试；网关 MAX_ATTEMPTS 仅限制同一模型 ID 的路由候选次数。PR-Agent ai_timeout 为 480 秒，网关 TOTAL_TIMEOUT_MS 默认 600 秒。

## 选择 runner

工作流默认使用 GitHub 托管 runner（`ubuntu-latest`），开箱即用，无需先准备机器。

要改成自托管，加一个仓库变量即可，不用改工作流：

```text
Settings -> Secrets and variables -> Actions -> Variables -> New repository variable
Name:  PR_AGENT_RUNNER_LABELS
Value: ["self-hosted", "Linux", "X64"]
```

变量值是 JSON 数组，可以填任意标签组合，例如 `["self-hosted", "PrismLLM"]`。删掉变量就回到 `ubuntu-latest`。

### 注册 self-hosted runner

`Settings -> Actions -> Runners -> New self-hosted runner`，选 Linux x64，按页面命令执行。PR-Agent 是 Docker Action，runner 上必须能跑 Docker：

```bash
sudo apt-get update && sudo apt-get install -y docker.io
sudo systemctl enable --now docker
sudo usermod -aG docker <RUNNER_USER>
sudo ./svc.sh stop && sudo ./svc.sh start
sudo docker run --rm hello-world
```

runner 需要能访问 `github.com`、`api.github.com`、Docker Hub，以及 `LLM_GATEWAY_BASE_URL` 指向的网关地址。用 `ubuntu-latest` 时访问不到内网地址。

### Cloudflare 前置要求

网关经 Cloudflare 隧道暴露时，GitHub runner 的出口 IP 会被 Cloudflare 判定为机器人，返回 `Just a moment...` 挑战页，请求到不了 AxonHub。

免费版只能用整体开关，WAF 自定义规则无法跳过 Bot Fight Mode：

- `Security -> Settings -> Bot traffic -> Bot Fight Mode` 关闭
- 如果 `Security Level` 是 `High` 或 `I'm Under Attack`，调回 `Medium`

API 已有 Bearer 鉴权，关掉机器人挑战不影响安全性；管理面如果也走同一域名，建议单独用子域名并保留验证。

## 用法

- 非草稿 PR 在创建、重新打开、标记 ready，以及推送新提交（synchronize）时都会自动审查；草稿 PR 的自动审查会全部跳过
- 在 PR-Agent 的行内审查线程下直接回复，PR-Agent 会自动结合最新代码、线程历史和该回复继续回答；无需手动添加 `/ask`
- PR-Agent 只有在核对当前代码后确认原问题已解决时，才会回复并自动 resolve 该线程；仍有问题时会在同一线程说明剩余事项。手动 `/ask` 只回答，不会自动 resolve
- 仅当自动审查未启动或需要手动重跑时，在 PR 顶层评论 `/review`；每次 push 已经会触发一次审查，无需再额外发送
- 删除分支等关闭事件不会触发审查；每次 push 都会消耗 token，审查使用 PR 当前分支中的最新配置
- PR 顶层评论里可用 `/review`、`/describe`、`/improve`、`/ask 这里的取消为什么会丢`
- 代码行内评论可用 `/ask 这条修复是否合理`；PR-Agent 会结合该行、线程和 PR diff 回复
- 自动线程回复和行内 `/ask` 都只接受仓库 `OWNER`、`MEMBER`、`COLLABORATOR` 的评论；非 PR-Agent 线程会被忽略
- 自动 resolve 需要工作流具备 `contents: write`，当前权限已配置在 `.github/workflows/pr-agent.yml`
- 若线程已回复但未 resolve，检查 `Settings -> Actions -> General -> Workflow permissions` 是否允许工作流读写仓库内容

## 成本

PR-Agent 只按你网关的渠道计费，成本取决于实际成功响应的模型：

- 每条自动线程回复都会触发一次模型调用，包括简短的确认回复
- 一次 20K 输入 + 4K 输出的审查，DeepSeek V4 Flash 约 `$0.005`，Gemini 3.8 Flash 约 `$0.03`
- 具体单价见各厂商定价页；这里不再复制价格，避免过期

已开的限流：64K 上下文、16K 输出、最多 5 条发现、关闭自动 describe/improve。网关侧还能按调用方密钥设并发和额度（路线图 v1.1）。

## 数据边界

自托管指 PR-Agent 跑在你的 runner 上；PR 的 diff 经你的网关转发给你自己配置的上游渠道。若网关在上游用了第三方向量厂商，代码仍会离开内网，按团队合规要求评估。

注意 GitHub 托管 runner 访问不到内网网关。网关只在内网时，必须用 self-hosted runner，并设置 `PR_AGENT_RUNNER_LABELS`。

## 排错

- `Model not found` / 网关返回 404：工作流中的模型 ID 未通过网关暴露，或模型/别名未启用
- `401`：`LLM_GATEWAY_KEY` 不是有效的调用方密钥；确认没有多余空格
- 连接超时 / `getaddrinfo` 失败：`LLM_GATEWAY_BASE_URL` 不可达，常见于用 `ubuntu-latest` 访问内网地址
- `404` 且路径重复：`LLM_GATEWAY_BASE_URL` 少了或多了 `/v1`，应为 `https://host/v1`
- `context length` 报错：把 `.pr_agent.toml` 的 `custom_model_max_tokens` 和 `max_model_tokens` 调到上游真实窗口以内
- `No runner matching labels`：注册 runner，或设置 `PR_AGENT_RUNNER_LABELS` 变量
- `permission denied` 访问 `docker.sock`：把 runner 用户加入 `docker` 组并重启服务
- 首次运行很慢：需要拉取 PR-Agent Docker 镜像
