-- Antigravity 的额度按模型家族分组（Gemini / Claude & GPT），每组各有 5h 与 weekly
-- 窗口。原有的 primary/secondary 仍保存第一组读数供调度使用；这里额外保存完整分组，
-- 让管理台能像上游一样展示每个家族的用量。
alter table oauth_accounts
    add column quota_groups text;
