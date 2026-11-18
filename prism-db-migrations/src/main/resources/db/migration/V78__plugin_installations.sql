-- 插件安装记录保存期望状态；进程实际加载版本由运行时报告。
-- 卸载保留 installed=false 的记录，防止默认初始化重新安装。
CREATE TABLE plugin_installations (
    plugin_id VARCHAR(80) PRIMARY KEY,
    plugin_version VARCHAR(80) NOT NULL,
    package_sha256 VARCHAR(64) NOT NULL,
    source VARCHAR(120) NOT NULL,
    installed BOOLEAN NOT NULL,
    desired_enabled BOOLEAN NOT NULL,
    approved_permissions_json TEXT NOT NULL DEFAULT '[]',
    configuration_json TEXT NOT NULL DEFAULT '{}',
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL,
    CONSTRAINT plugin_installation_id CHECK (plugin_id ~ '^[a-z][a-z0-9._-]{0,79}$'),
    CONSTRAINT plugin_installation_digest CHECK (package_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT plugin_installation_revision CHECK (revision >= 0),
    CONSTRAINT plugin_installation_enabled CHECK (NOT desired_enabled OR installed),
    CONSTRAINT plugin_installation_permissions CHECK (jsonb_typeof(approved_permissions_json::jsonb) = 'array'),
    CONSTRAINT plugin_installation_configuration CHECK (jsonb_typeof(configuration_json::jsonb) = 'object')
);
