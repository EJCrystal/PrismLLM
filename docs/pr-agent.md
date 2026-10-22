# PR-Agent 代码审查

仓库使用 PR-Agent（https://github.com/The-PR-Agent/pr-agent）做自动代码审查，模型通过自建 llm-gateway 调用。

- 工作流：`.github/workflows/pr-agent.yml`
- 审查规则：`.pr_agent.toml`
- 请求发往自建网关的 OpenAI 兼容入口（`/v1/chat/completions`），用调用方密钥鉴权
- 模型名是网关里的别名，由仓库变量 `LLM_GATEWAY_REVIEW_MODEL` 指定

## 必须做的配置

### 1. Secret：网关调用方密钥

`Settings -> Secrets and variables -> Actions -> Secrets -> New repository secret`：

```text
LLM_GATEWAY_KEY = <网关管理界面创建的调用方密钥>
```

用命令行设置，值不经过聊天记录：

```bash
cd /path/to/llm-gateway
gh secret set LLM_GATEWAY_KEY
```

这个密钥在管理界面「调用方密钥」里创建，明文只显示一次。

### 2. Variable：网关地址和模型别名

`Settings -> Secrets and variables -> Actions -> Variables`：

```text
LLM_GATEWAY_BASE_URL     = https://gateway.internal.example.com/v1
LLM_GATEWAY_REVIEW_MODEL = <网关里指向审查模型的别名>
```

走命令行：

```bash
gh variable set LLM_GATEWAY_BASE_URL --body "https://gateway.internal.example.com/v1"
gh variable set LLM_GATEWAY_REVIEW_MODEL --body "review-model"
```

地址必须**包含 `/v1`**，与 README 里渠道 `baseUrl` 的约定一致；PR-Agent 自己追加 `/chat/completions`，不会补 `/v1`。

## 自定义 URL 的原理

工作流用三个变量把 PR-Agent 指向你的网关：

```yaml
OPENAI__KEY: ${{ secrets.LLM_GATEWAY_KEY }}
OPENAI__API_BASE: ${{ vars.LLM_GATEWAY_BASE_URL }}
CONFIG__MODEL: openai/${{ vars.LLM_GATEWAY_REVIEW_MODEL || 'review-model' }}
CONFIG__FALLBACK_MODELS: '[]'
```

`openai/` 前缀让 LiteLLM 走 OpenAI 兼容路径，实际请求发到 `OPENAI__API_BASE`，模型名用你网关里的别名。任何 OpenAI 兼容网关都能这样接。

## 上游模型与回退

主模型和回退都在网关内部完成，不依赖 PR-Agent。要换审查模型，只改仓库变量 `LLM_GATEWAY_REVIEW_MODEL`，或在网关管理界面把该别名指向另一个渠道，不必改工作流。

不建议在 `CONFIG__FALLBACK_MODELS` 里写 `openai/xxx`：这些名称都会带着同一个 `api_base` 和密钥发出，网关若不认识就会直接报错。让网关按渠道做重试和切换更可靠。

## 选择 runner

工作流默认使用 GitHub 托管 runner（`ubuntu-latest`），开箱即用，无需先准备机器。

要改成自托管，加一个仓库变量即可，不用改工作流：

```text
Settings -> Secrets and variables -> Actions -> Variables -> New repository variable
Name:  PR_AGENT_RUNNER_LABELS
Value: ["self-hosted", "Linux", "X64"]
```

变量值是 JSON 数组，可以填任意标签组合，例如 `["self-hosted", "llm-gateway"]`。删掉变量就回到 `ubuntu-latest`。

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

- PR 创建、重新打开、标记 ready 时自动审查，草稿 PR 跳过
- 后续 push 默认不重复审查，节省 token
- PR 评论里可用 `/review`、`/describe`、`/improve`、`/ask 这里的取消为什么会丢`

需要每次 push 都自动审查时，把工作流的 `on.pull_request.types` 加上 `synchronize`，并把 `GITHUB_ACTION_CONFIG__PR_ACTIONS` 也加上 `synchronize`。

## 成本

PR-Agent 只按你网关的渠道计费，成本取决于 `LLM_GATEWAY_REVIEW_MODEL` 背后的上游模型：

- 一次 20K 输入 + 4K 输出的审查，DeepSeek V4 Flash 约 `$0.005`，Gemini 3.8 Flash 约 `$0.03`
- 具体单价见各厂商定价页；这里不再复制价格，避免过期

已开的限流：64K 上下文、16K 输出、最多 5 条发现、关闭自动 describe/improve。网关侧还能按调用方密钥设并发和额度（路线图 v1.1）。

## 数据边界

自托管指 PR-Agent 跑在你的 runner 上；PR 的 diff 经你的网关转发给你自己配置的上游渠道。若网关在上游用了第三方向量厂商，代码仍会离开内网，按团队合规要求评估。

注意 GitHub 托管 runner 访问不到内网网关。网关只在内网时，必须用 self-hosted runner，并设置 `PR_AGENT_RUNNER_LABELS`。

## 排错

- `Model not found` / 网关返回 404：`LLM_GATEWAY_REVIEW_MODEL` 与网关里的别名不一致，或别名未启用
- `401`：`LLM_GATEWAY_KEY` 不是有效的调用方密钥；确认没有多余空格
- 连接超时 / `getaddrinfo` 失败：`LLM_GATEWAY_BASE_URL` 不可达，常见于用 `ubuntu-latest` 访问内网地址
- `404` 且路径重复：`LLM_GATEWAY_BASE_URL` 少了或多了 `/v1`，应为 `https://host/v1`
- `context length` 报错：把 `.pr_agent.toml` 的 `custom_model_max_tokens` 和 `max_model_tokens` 调到上游真实窗口以内
- `No runner matching labels`：注册 runner，或设置 `PR_AGENT_RUNNER_LABELS` 变量
- `permission denied` 访问 `docker.sock`：把 runner 用户加入 `docker` 组并重启服务
- 首次运行很慢：需要拉取 PR-Agent Docker 镜像
