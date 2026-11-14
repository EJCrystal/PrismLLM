-- reasoning_content 与 developer 角色的处理已改为按上游 host / 模型名自动判定（对齐 magpie），
-- 两个渠道级开关（reasoningContentPolicy、developerRolePolicy）删除。
-- 这里清理历史行里残留的配置键，否则仍按默认 ignoreUnknownKeys=false 解码的旧实例会读不出配置。
--
-- jsonb_exists_any 是 ?| 的函数形式：JDBC 下 ? 会被当成参数占位符。
update channels
set request_overrides =
        (request_overrides::jsonb - 'reasoningContentPolicy' - 'developerRolePolicy')::text
where jsonb_exists_any(request_overrides::jsonb,
                       array ['reasoningContentPolicy', 'developerRolePolicy']);

update upstream_request_settings
set overrides = (overrides::jsonb - 'reasoningContentPolicy' - 'developerRolePolicy')::text
where jsonb_exists_any(overrides::jsonb,
                       array ['reasoningContentPolicy', 'developerRolePolicy']);
