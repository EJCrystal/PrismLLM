-- 历史渠道只保留网关支持的协议，并按固定优先级排序；空值、未知协议全部回退为 Chat。
update channels
set protocols = coalesce(
    (
        select string_agg(candidate, ',' order by ord)
        from unnest(array['chat_completions', 'responses', 'messages']) with ordinality as supported(candidate, ord)
        where candidate in (
            select btrim(part)
            from regexp_split_to_table(coalesce(channels.protocols, ''), ',') as parts(part)
        )
    ),
    'chat_completions'
);

alter table request_logs
    add column upstream_protocol varchar(40);
