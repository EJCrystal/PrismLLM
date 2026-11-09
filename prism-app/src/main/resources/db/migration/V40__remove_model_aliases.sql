-- 直连暴露已覆盖当前路由需求：渠道启用并按对外模型 ID（canonical ID 或上游模型名）聚合同一模型。
-- 别名机制整体移除，后续如需按模型单独编排跨渠道顺序再以新机制引入。
drop table if exists alias_candidates;
drop table if exists model_aliases;
