# V57 渠道级密钥自动禁用策略升级说明

V57 给自动禁用规则增加渠道级覆盖，并把此前配置在全局的 Grox 规则「活动已关闭或Key失效」自动搬进 Grox 渠道的覆盖策略。

## 结构变更

- `channels.key_disable_policy text`：`null` 表示继承全局；非空时保存 `{"enabled": bool, "rules": [...]}`。`enabled = true` 时渠道规则排在全局规则之前，未命中再回落全局；`enabled = false` 时本渠道不执行任何规则（渠道与全局都不生效），此时 `rules` 允许为空。
- `channels.key_disable_policy_revision bigint not null default 0`：每次保存覆盖策略自增，用于让旧失败计数失效。
- `channel_key_health.policy_scope varchar(80) not null default 'global'`：计数归属的作用域，取值 `global` 或 `channel:<id>`。升级前的计数都来自全局策略，默认值保持这一归属不变。
- `channel_key_health.policy_revision bigint`：升级后存的是**有效策略指纹**（作用域 + 合并后策略 JSON 的 SHA-256 截断），而不是渠道版本号。渠道覆盖叠加全局兜底后，全局规则变化同样会让旧计数失效，只比版本号会漏掉这种情况。升级前写入的是全局设置版本号，与新指纹必然不匹配，因此**升级后每个密钥的失败计数会从零重计一次**——只影响计数，不改任何密钥状态。

三处变更都是加列，没有改写或删除既有行（下面的数据搬迁除外），无需维护窗口。

## 一次性数据搬迁

迁移在一个事务内执行，两步写成**一条** `WITH ... AS (UPDATE ... RETURNING) UPDATE` 语句，保证删除全局规则的前提是**本次搬迁确实写入了** Grox 覆盖策略：

1. 若全局规则里存在 `id = 'activity_disabled'` 的规则，把它连同当时的全局 `enabled` 和完整规则列表写入名称为 `Grox` 的渠道（仅当该渠道尚无覆盖策略），并把该规则的 `threshold` 固化为 `1`——`DEEPSEEK_ACTIVITY_DISABLED` 是上游终态错误，实际事件间隔远超 5 分钟窗口，阈值 2 永远不会触发。这一步通过 `returning` 暴露「是否真的写入」。
2. 只有第一步返回了行，才从 `key_auto_disable_settings.rules` 中删除 `activity_disabled` 并把 `revision` 加一，使全局计数重新开始。

这个条件比「Grox 覆盖里恰好有同 id 规则」更严格：如果 Grox 本来就有一份含 `activity_disabled` 的覆盖策略（管理员此前独立配置的，不是本次迁移写入的），全局规则仍然保留。

如果全局规则里没有这条规则，或者第一步被跳过（数据库里不存在名为 `Grox` 的渠道，或该渠道已有覆盖策略），整条语句不会产生任何改动——全局规则原样保留，可以安全地在任意环境执行。

搬迁写入的是一份**完整副本**（当时全局的全部规则 + `activity_disabled`）。后续代码改为「渠道优先 + 全局兜底、同 id 由渠道版本接管」后，副本里与全局同 id 的规则会遮蔽全局版本，Grox 不再跟随全局规则更新（这是沿用旧副本的既定行为，不是回归）。若该渠道本就该只保留上游特有规则，在管理台把多余的规则删掉后保存即可；下面这条只读查询可以确认哪些覆盖策略还带着全局规则副本：

```sql
select c.name,
       (select count(*) from jsonb_array_elements(c.key_disable_policy::jsonb -> 'rules') as r(elem)
        where exists (
            select 1
            from jsonb_array_elements((select rules::jsonb from key_auto_disable_settings where id = 1)) as g(elem)
            where g.elem ->> 'id' = r.elem ->> 'id'
        )) as copied_global_rules
from channels c
where c.key_disable_policy is not null;
```

`copied_global_rules = 0` 表示该渠道只保存了渠道独有规则，会完整跟随全局更新。

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
- 其它渠道不受影响，继续继承全局规则。需要在管理台调整时，进入渠道操作菜单「密钥自动禁用」：选择自定义后保存即建立覆盖（渠道规则优先，未命中回落全局），选择关闭后保存即停用本渠道的自动禁用（等价于 `PUT` 时传 `{"override":{"enabled":false,"rules":[]}}`），选择继承后保存即清除覆盖（等价于 `PUT` 时传 `{"override": null}`）。

## 回滚

结构变更本身可回退（drop 三列），但**不建议**直接在已升级库上执行反向 SQL：Flyway 会因 checksum 与 schema 版本不一致无法启动旧版本。需要回退时按常规流程恢复部署前备份，再启动旧镜像。
