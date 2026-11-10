-- 不同 Antigravity 账号的套餐/开通方式不同，上游返回的模型目录也不同（例如 Pixel 与
-- 正价账号可用的模型家族不一致）。目录必须按账号保存，不能像以前那样把某个账号的
-- 目录写进共享的系统渠道。
--
-- model_catalog: JSON 数组，元素为 {upstreamModel, canonicalModelId?}；null 表示尚未同步。
-- model_catalog_synced_at: 最近一次成功同步时间，null 表示从未同步。
alter table oauth_accounts
    add column model_catalog text;
alter table oauth_accounts
    add column model_catalog_synced_at bigint;