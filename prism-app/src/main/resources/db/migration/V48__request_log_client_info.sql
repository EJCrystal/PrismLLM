-- 请求来源信息：调用方 IP 与 User-Agent，用于管理面请求日志展示与排查。
alter table request_logs add column client_ip varchar(64);
alter table request_logs add column client_user_agent varchar(300);
