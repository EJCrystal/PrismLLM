# V40 别名移除升级说明

V40 会删除 `model_aliases` 和 `alias_candidates`，不会把历史别名自动转换为渠道的 canonical ID。该迁移是有意的破坏性变更，与当前仅使用直连模型路由的配置一致。

部署前请：

1. 备份数据库。
2. 检查现有别名配置和调用方，确认调用方已改用渠道对外暴露的 canonical ID 或上游模型 ID。
3. 发布后检查 `/v1/models`，确认需要的对外模型仍存在。

迁移完成后，继续使用已删除别名的请求会返回 `model_not_found`（HTTP 404）。
