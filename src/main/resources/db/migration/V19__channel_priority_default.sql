-- 渠道优先级取值范围调整为 1..1000，默认 1（数值越小越优先）。
-- 存量整体 +1 平移：原 0（默认/最高档）变 1，原 1（次档）变 2，既保证新默认值，
-- 又不抹平已配置渠道之间的档位关系；原上限 999 平移后仍在 1..1000 内。
alter table channels
    alter column priority set default 1;

update channels
    set priority = priority + 1;
