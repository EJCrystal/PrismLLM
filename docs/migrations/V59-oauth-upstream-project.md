# V59 Antigravity 上游上下文

V59 只做一件事：给 `oauth_accounts` 增加三列，保存 Code Assist 需要的账号级上下文。

| 列 | 用途 |
| --- | --- |
| `upstream_project` | 账号的 Google Cloud project（`cloudaicompanionProject`） |
| `upstream_tier` | 当前 tier id，用于 `fetchAvailableModels` |
| `upstream_tier_checked_at` | 上次读取时间，用于一小时缓存 |

## 特性

- 纯增量：不改动已有行，不影响凭据、调度策略与额度快照。
- 已有账号三列为空，第一次 Code Assist 请求时由 `loadCodeAssist` /
  `onboardUser` 自动补齐。
- 不需要维护窗口，也不阻塞启动。
- 读取失败时沿用上一次的值；从未成功过时请求照常发出，只是不带 project。

## 回滚

回滚只需要把三列删掉：

```sql
alter table oauth_accounts
    drop column upstream_project,
    drop column upstream_tier,
    drop column upstream_tier_checked_at;
```

丢失的只是缓存，账号与凭据不受影响。

## 迁移后校验

```sql
select count(*) filter (where upstream_project is not null) as with_project,
       count(*) filter (where upstream_tier_checked_at is not null) as checked
from oauth_accounts;
```
