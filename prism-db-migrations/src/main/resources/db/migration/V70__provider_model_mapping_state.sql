ALTER TABLE channels
    ADD COLUMN model_mapping_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE channel_models
    ADD COLUMN catalog_active BOOLEAN NOT NULL DEFAULT TRUE;

-- 既有 models_dev 建议属于已确认覆盖；保留来源，不转换为自动映射。
-- 普通渠道的旧行全部 active，账号授权及 []/显式零授权哨兵均不改动。
