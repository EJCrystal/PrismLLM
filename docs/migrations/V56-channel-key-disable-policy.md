# V56 渠道级密钥自动禁用策略升级说明

V56 给自动禁用规则增加渠道级覆盖，并把此前配置在全局的 Grox 规则「活动已关闭或Key失效」自动搬进 Grox 渠道的覆盖策略。

## 结构变更

- `channels.key_disable_policy text`：`null` 表示继承全局；非空时保存 `{"enabled": bool, "rules": [...]}`，**整表替换**全局策略，不与全局规则合并。
- `channels.key_disable_policy_revision bigint not null default 0`：每次保存覆盖策略自增，用于让旧失败计数失效。
- `channel_key_health.policy_scope varchar(80) not null default 'global'`：计数归属的作用域，取值 `global` 或 `channel:<id>`。升级前的计数都来自全局策略，默认值与 `key_auto_disable_settings.revision` 配合，未改名渠道的既有计数继续有效。

三处变更都是加列，没有改写或删除既有行（下面的数据搬迁除外），无需维护窗口。

## 一次性数据搬迁

迁移在一个事务内执行，两步写成**一条** `WITH ... AS (UPDATE ... RETURNING) UPDATE` 语句，保证删除全局规则的前提是**本次搬迁确实写入了** Grox 覆盖策略：

1. 若全局规则里存在 `id = 'activity_disabled'` 的规则，把它连同当时的全局 `enabled` 和完整规则列表写入名称为 `Grox` 的渠道（仅当该渠道尚无覆盖策略），并把该规则的 `threshold` 固化为 `1`——`DEEPSEEK_ACTIVITY_DISABLED` 是上游终态错误，实际事件间隔远超 5 分钟窗口，阈值 2 永远不会触发。这一步通过 `returning` 暴露「是否真的写入」。
2. 只有第一步返回了行，才从 `key_auto_disable_settings.rules` 中删除 `activity_disabled` 并把 `revision` 加一，使全局计数重新开始。

这个条件比「Grox 覆盖里恰好有同 id 规则」更严格：如果 Grox 本来就有一份含 `activity_disabled` 的覆盖策略（管理员此前独立配置的，不是本次迁移写入的），全局规则仍然保留。

如果全局规则里没有这条规则，或者第一步被跳过（数据库里不存在名为 `Grox` 的渠道，或该渠道已有覆盖策略），整条语句不会产生任何改动——全局规则原样保留，可以安全地在任意环境执行。

## 部署后

- 原先被这条规则自动停用的密钥**不会**被迁移恢复状态；它们仍是 `AUTO_PERMANENT`，需要在渠道密钥列表人工启用。Grox 的手工停用（`MANUAL_DISABLED`）同样保持原状。
- 确认搬迁结果：

  ```sql
  -- Grox 应出现覆盖策略，rules 中含 activity_disabled 且 threshold = 1
  select name, key_disable_policy_revision,
         jsonb_array_elements(key_disable_policy::jsonb -> 'rules') ->> 'id' as rule_id
  from channels
  where name = 'Grox' and key_disable_policy is not null;
  -- 全局规则已不再包含 activity_disabled
  select rules from key_auto_disable_settings where id = 1;
  ```
- 其它渠道不受影响，继续继承全局规则。需要在管理台调整时，进入渠道操作菜单「密钥自动禁用」：选择自定义后保存即建立覆盖，选择继承后保存即清除覆盖（等价于 `PUT` 时传 `{"override": null}`）。

## 回滚

结构变更本身可回退（drop 三列），但**不建议**直接在已升级库上执行反向 SQL：Flyway 会因 checksum 与 schema 版本不一致无法启动旧版本。需要回退时按常规流程恢复部署前备份，再启动旧镜像。
