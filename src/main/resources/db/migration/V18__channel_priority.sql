-- 渠道优先级：直连候选按渠道优先级分档，同档轮询分摊，低档仅在高档失败或冷却时启用。
alter table channels
    add column if not exists priority integer not null default 0;
