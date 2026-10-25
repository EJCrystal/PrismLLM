-- 渠道优先级取值范围调整为 1..1000，默认 1（数值越小越优先）。
alter table channels
    alter column priority set default 1;

update channels
    set priority = 1
    where priority = 0;
