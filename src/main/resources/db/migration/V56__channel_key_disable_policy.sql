-- 渠道级密钥自动禁用策略：为空表示继承全局设置，非空时整表替换全局策略。
alter table channels
    add column key_disable_policy text,
    add column key_disable_policy_revision bigint not null default 0;

-- 计数归属需要区分作用域：全局策略变更不能清掉渠道策略下的计数，反之亦然。
-- 升级前的计数都来自全局策略，默认值保持它们继续有效。
alter table channel_key_health
    add column policy_scope varchar(80) not null default 'global';

-- 「活动已关闭或Key失效」的 DEEPSEEK_ACTIVITY_DISABLED 只由 Grox 上游返回，
-- 放在全局会误伤其他渠道。固化到 Grox 的渠道覆盖策略，并把阈值收紧为 1：
-- 这是上游终态错误，等不到 5 分钟窗口内的第二次。
update channels c
set key_disable_policy = (
        select jsonb_build_object(
            'enabled', s.enabled,
            'rules', coalesce((
                select jsonb_agg(
                    case
                        when elem ->> 'id' = 'activity_disabled' then elem || '{"threshold":1}'::jsonb
                        else elem
                    end
                    order by ord
                )
                from jsonb_array_elements(s.rules::jsonb) with ordinality as t(elem, ord)
            ), '[]'::jsonb)
        )::text
        from key_auto_disable_settings s
        where s.id = 1
    ),
    key_disable_policy_revision = 1
where c.name = 'Grox'
  and c.key_disable_policy is null
  and exists (
      select 1
      from key_auto_disable_settings s
      where s.id = 1
        and s.rules is not null
        and jsonb_typeof(s.rules::jsonb) = 'array'
        and s.rules::jsonb @> '[{"id":"activity_disabled"}]'::jsonb
  );

update key_auto_disable_settings
set rules = (
        select coalesce(jsonb_agg(elem order by ord), '[]'::jsonb)::text
        from jsonb_array_elements(rules::jsonb) with ordinality as t(elem, ord)
        where elem ->> 'id' is distinct from 'activity_disabled'
    ),
    revision = revision + 1
where id = 1
  and rules is not null
  and jsonb_typeof(rules::jsonb) = 'array'
  and rules::jsonb @> '[{"id":"activity_disabled"}]'::jsonb;
