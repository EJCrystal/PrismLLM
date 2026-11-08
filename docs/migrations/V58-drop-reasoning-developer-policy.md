# V58 移除 reasoning_content 与 developer 角色开关

V58 删除渠道级请求覆盖里的两个开关，处理规则改为按上游自动判定，与 magpie 对齐。

## 为什么删

- `reasoningContentPolicy` 和 `developerRolePolicy` 是照着 magpie 的思路补的可配置项，但 magpie 本身没有这两个开关：它靠 host / 模型名硬判定、形状拒绝词表和失败后的记忆自动处理。
- 实际配置量极低：升级前全库只有一个渠道（`Bizdecipher`）设了 `reasoningContentPolicy: always`，而它的模型名 `deepseek-flash` 在自动判定下本来就会命中；没有任何渠道设置 developer 策略，全局两项都为空。
- 原来的自动判定给**每一条** assistant 消息补 `reasoning_content: ""`，比 magpie 激进得多：只给工具轮补值，且回退值是单个空格。前者会给 Groq / Mistral 这类以 `extra_forbidden` 拒绝该字段的上游制造 400。

## 结构变更

没有表结构变更，只清理 `text` 列里 JSON 的键：

- `channels.request_overrides`
- `upstream_request_settings.overrides`

迁移用 `(col::jsonb - 'key')::text` 删除 `reasoningContentPolicy` 与 `developerRolePolicy`，并用 `jsonb_exists_any` 限定只改含这两个键的行。不用 `?|` 是因为 JDBC 会把 `?` 当成参数占位符。

删除是幂等的：键不存在时 `where` 不命中，不会重写整表。

## 代码侧配合

两个仓库读取这两列时改用 `Json { ignoreUnknownKeys = true }`。这是滚动升级的安全网：迁移执行后，尚未升级的旧实例仍可能把旧键写回；新实例不能因此读不出渠道配置。

## 回滚

数据本身可恢复：两个键的值不影响任何行为（旧版本才有意义），需要回退时按常规流程恢复部署前备份，再启动旧镜像。Flyway 会因 checksum 与 schema 版本不一致拒绝在已升级库上直接启动旧版本，不要手工执行反向 SQL。
