-- 渠道级直连开关移除：是否参与直连只由渠道启用状态决定。
-- 原关闭直连的渠道转为停用，保持直连不可达；注意若此类渠道曾被别名引用，
-- 别名候选将随渠道停用而失效，需继续使用时请重新启用渠道（其模型将按对外模型 ID 直连暴露）。
update channels
    set enabled = false
    where expose_upstream_models = false;

alter table channels
    drop column if exists expose_upstream_models;
