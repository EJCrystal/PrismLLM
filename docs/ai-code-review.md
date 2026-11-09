# AI 代码审查门禁（CodeRabbit → PR-Agent）

`main` 分支要求 `AI Review Gate` 状态检查通过才能合并。该状态由
`.github/workflows/pr-agent.yml` 维护，按固定优先级两级降级：

| 级别 | 审查方 | 放行条件 | 不可用时 |
| --- | --- | --- | --- |
| 1 | CodeRabbit | check run 成功且不是限流结论 | 邀请后仍跳过、限流、失败、邀请后无响应 → 降级 |
| 2 | PR-Agent | 审查产出落地且没有未解决的 ACTIVE 意见 | 门禁判失败，需人工处理 |

## 触发与等待

- PR 打开、推新 commit、重新打开、转为可审查：先置 `pending`，再等 CodeRabbit 接单。
- CodeRabbit 在本仓库是 OSS 仓库，自动审查会返回 `Review skipped: manual review required`（结论
  `skipped`）。这不代表服务不可用：workflow 会先用 PAT 发 `@coderabbitai review` 邀请它人工接单，
  并等待 5 分钟；只有邀请后仍返回 `skipped`、限流或完全无响应才降级。
- PR 事件本身也会等 60 秒观察 CodeRabbit 是否创建 check run。观察窗口结束时：
  - 已接单（running）→ 交给后续 check_run 事件处理；
  - 返回 `skipped` 或窗口内完全没有 check run → 先邀请 CodeRabbit，走上一条的等待逻辑；
  - check run 失败或取消 → 属于偶发，按 commit 级降级，不会干等后续事件。
- 降级到 PR-Agent 时发 `/review`，由 `pr_agent_review` job 执行并把审查结论写回门禁。

## 降级范围

- **PR 级**（限流、邀请 CodeRabbit 后仍返回 `skipped` 或无响应）：在该 PR 的评论里写隐藏标记
  `<!-- ai-review-tier:pr-agent scope=pr -->`，后续 commit 直接沿用 PR-Agent，不再反复邀请失效的服务。
- **commit 级**（单次 check run 失败、取消）：在该 commit 上写
  `<!-- ai-review-tier:pr-agent scope=commit sha=<sha> -->`，只影响当前 commit，下一个 commit 仍从 CodeRabbit 开始。
- 标记只认 `github-actions[bot]` 发的评论，人工或第三方伪造的同名标记会被忽略。
- 门禁一旦置为 `success`，后续 CodeRabbit 的失败事件不会把它改回 `pending` 或 `failure`；
  但 PR-Agent 的裁决（含 ACTIVE 意见）永远以 PR-Agent 结果为准。

## 所需配置

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Secret | `PR_AGENT_TRIGGER_TOKEN` | 以真实用户身份发触发评论；`GITHUB_TOKEN` 发的评论不会触发新 workflow，机器人也不响应 |
| Secret | `LLM_GATEWAY_KEY` | PR-Agent 调用本仓库 LLM Gateway 的凭据 |
| Variable | `LLM_GATEWAY_BASE_URL` | PR-Agent 的 OpenAI 兼容端点 |
| Variable | `LLM_GATEWAY_REVIEW_MODEL` | PR-Agent 使用的模型名 |
| Variable | `LLM_GATEWAY_REVIEW_MODEL_REASONING_EFFORT` | PR-Agent 推理等级（可选） |

## 手动重跑

Actions → **AI Review Gatekeeper** → Run workflow：

- `pr_number`：目标 PR 编号。
- `tier=auto`：沿用 PR 当前档位继续（默认）；CodeRabbit 已审完则直接放行，没审则先邀请它。
- `tier=pr-agent`：跳过 CodeRabbit，直接发 `/review` 使用 PR-Agent 兜底。

人工在 PR 里评论 `/review` 会由 `pr_agent_review` job 直接跑 PR-Agent，并把该 commit 视为 PR-Agent 档位。
