-- Per-target Feishu card templates; NULL keeps the built-in default layout.
ALTER TABLE notification_targets
    ADD COLUMN template_title TEXT,
    ADD COLUMN template_body TEXT;
