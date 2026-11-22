CREATE TABLE system_prompt_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    settings TEXT NOT NULL,
    updated_at BIGINT NOT NULL DEFAULT 0
);
