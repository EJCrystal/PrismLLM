ALTER TABLE plugin_installations
    ADD COLUMN disable_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT plugin_installation_disable_generation CHECK (disable_generation >= 0);
