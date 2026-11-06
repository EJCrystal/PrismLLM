# V52 / V53 订阅账号解耦升级说明

V52 把订阅账号从 `channel_keys` 中拆出来：旧 OAuth 密钥的 `key_id` 直接复用为新账号 id，refresh token 从 `channel_keys.credential_*` 搬到 `oauth_accounts.refresh_token_*`，模型授权、渠道关联与窗口快照一并迁移，最后删除这些只用于承载 OAuth 账号的密钥，并 drop `channel_key_oauth_accounts` 与 `channel_key_oauth_windows`。

V53 只新增 `oauth_accounts.quota_attempted_at`，用于额度扫描排队。

V52 是**不可逆**的破坏性迁移，且会删除数据行，不是纯结构变更。脚本已发布，**不要修改**，否则已执行过它的环境会因 Flyway checksum 不匹配而无法启动。

## 部署前

1. 备份数据库。迁移会删除 `channel_keys` 行并 drop 两张表，没有反向脚本可以还原。
2. 停止旧实例，只让新版本启动并完成迁移后再恢复流量；迁移在单个 Flyway 事务内执行，失败会整体回滚。
3. **核对即将被固化为停用的账号。** V52 把非 `ACTIVE` 的密钥统一迁成 `enabled=false`，而新模型没有“到期自动恢复”的中间态。升级前先执行：

   ```sql
   select a.key_id, a.provider, a.account_email, k.state, k.auto_disable_until
   from channel_key_oauth_accounts a
   join channel_keys k on k.id = a.key_id
   where k.state = 'AUTO_TEMPORARY';
   ```

   结果里的账号升级后需要在账号页手工重新启用。该映射无法在迁移里事后修正——V52 执行完，状态信息就已经丢失。

## 迁移语义

- `ACTIVE` 迁成启用账号；`MANUAL_DISABLED` / `AUTO_TEMPORARY` / `AUTO_PERMANENT` 迁成停用账号，`auto_disable_until` / `auto_disable_reason` 不再保留。
- `channel_key_models` 的模型白名单迁到 `oauth_account_models`，空列表表示继承所关联渠道的全部模型。
- `channel_key_oauth_windows` 的窗口命中、冷却与连续 429 计数迁到 `oauth_account_windows`。
- `channel_key_health`、`channel_key_rate_limits`、`channel_key_quota_settings` 随密钥级联删除，不迁移。

## V53：额度扫描排队

额度扫描原本按 `quota_checked_at` 排序，而读取失败不写这一列，持续失败的账号会永远排在队首；账号数超过一轮上限（100）时，其他账号的额度刷新会被饿死。

V53 新增 `quota_attempted_at`（最近一次尝试，成功或失败），扫描改用它排序；`quota_checked_at` 仍然只表示最近一次**成功**读数的新鲜度，供调度判断额度快照是否可用。迁移时用 `quota_checked_at` 回填，保持原有刷新节奏。

## 限流配置

**账号级窗口与并发取代了密钥级限流，这是有意的。** 订阅账号按账号统计 `window_limit` / `window_seconds` / `max_concurrent_requests`，不再走 `ChannelKeyRateLimiter`。如果之前给某个 OAuth 密钥单独配过 `channel_key_rate_limits` 的每分钟请求数或并发上限，迁移后这些设置不会生效，需要按账号重新配置窗口与并发。

## 回滚

没有反向迁移。回退旧镜像时，旧版本本地没有 V52，Flyway 默认开启 `validateOnMigrate`，会因“已应用的迁移在本地不存在”直接启动失败。可行的回滚只有：

1. 回退镜像到上一个版本；
2. 从部署前备份恢复数据库；
3. 启动旧版本并确认 `flyway_schema_history` 最高版本回到 51。

## 迁移后校验

```sql
-- 账号数应与迁移前 OAuth 密钥数一致
select count(*) from oauth_accounts;
-- 不应再有 OAuth 专用密钥残留
select count(*) from channel_keys k join oauth_accounts a on a.id = k.id;
-- 旧表应已不存在
select table_name from information_schema.tables
where table_name in ('channel_key_oauth_accounts', 'channel_key_oauth_windows');
-- V53 已回填首次尝试时间
select count(*) from oauth_accounts where quota_attempted_at is null and quota_checked_at is not null;
```
