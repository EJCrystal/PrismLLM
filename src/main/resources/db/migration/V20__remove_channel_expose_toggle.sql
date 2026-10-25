-- 渠道级直连开关移除：是否参与直连只由渠道启用状态与全局 EXPOSE_UPSTREAM_MODELS 决定。
-- 原关闭直连的渠道转为停用，保持“不可达”行为不变。
update channels
    set enabled = false
    where expose_upstream_models = false;

alter table channels
    drop column if exists expose_upstream_models;
