# V65 订阅账号授权时间

V65 只做一件事：给 `oauth_accounts` 增加 `authorized_at`，记录 refresh token 的获取时间。

| 列 | 用途 |
| --- | --- |
| `authorized_at` | 管理员首次导入/浏览器登录，或重新授权时的时间 |

## 为什么不能直接用 access token 到期时间

OAuth 的 access token 是短期凭据，ChatGPT 与 Antigravity 通常只有约 1 小时有效期。网关会在到期前用 refresh token 自动续期，所以 `access_token_expires_at` 只说明下一个短期 token 何时失效，不代表账号授权失效。管理面以前只展示这个时间，容易让人误以为「授权后一小时就过期」。

`authorized_at` 只在 refresh token 被替换时更新：

- 新建账号（导入 refresh token 或浏览器登录）时等于创建时间。
- 重新授权、导入新的 refresh token 时更新为本次操作时间。
- 日常 access token 刷新、refresh token 轮换不改变，因为它仍属于同一次授权。

## 回滚

回滚只需要删除该列：

```sql
alter table oauth_accounts
    drop column authorized_at;
```

回滚后管理面会缺少授权时间展示，账号与凭据不受影响。

## 迁移后校验

```sql
select count(*) as accounts,
       count(*) filter (where authorized_at is not null) as with_authorized_at
from oauth_accounts;
```

已有账号在迁移时用 `created_at` 回填，因此升级后不会出现空值。