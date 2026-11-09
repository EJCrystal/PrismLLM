# PR-Agent 误报豁免

`AI Review Gate` 会读取 **base 分支** 的 `.github/pr-agent-waivers.json`，按 `finding_id` 排除已人工确认的误报。豁免文件必须合入 main，不能由待审查的 PR 自己添加。

```json
{
  "version": 1,
  "waivers": [
    {
      "finding_id": "0123456789ab",
      "reason": "false positive: helper 定义在 GatewayIntegrationTestBase",
      "expires": "2027-01-01"
    }
  ]
}
```

- `finding_id`：PR-Agent 状态块里的十六进制 ID，必填。
- `reason`：人工复核结论，必填。
- `expires`：可选，`YYYY-MM-DD`；过期后豁免自动失效。建议给每条例外一个复核期限。

`AI Review Gate` 还会自动忽略 **stale** finding：finding 正文里引用的代码表达式已经不在其标注行附近（默认 ±10 行）时，说明描述对应的是旧代码，不再阻塞合并。stale 判定是启发式的，仅作为降噪，不会替代人工复核。
