# V40 别名移除升级说明

V40 会删除 `model_aliases` 和 `alias_candidates`，不会把历史别名自动转换为渠道的 canonical ID。该迁移是有意的破坏性变更，与当前仅使用直连模型路由的配置一致。

部署前请：

1. 备份数据库。
2. 检查现有别名配置和调用方，确认调用方已改用 `/v1/models` 返回的对外模型 ID。已配置 canonical ID 的上游模型只能用 canonical ID 调用，原始上游模型名会返回 `model_not_found`；未配置 canonical ID 的上游模型才可直接用上游模型名调用。
3. 发布后检查 `/v1/models`，确认需要的对外模型仍存在。

迁移完成后，继续使用已删除别名的请求会返回 `model_not_found`（HTTP 404）。

> 补充（后续版本）：`404 model_not_found` 只表示模型未配置在任何启用渠道中。若模型已配置、但支持它的凭据全部停用，现在返回 `503 model_credentials_unavailable`，客户端应按暂时不可用重试，而不是当成配置错误。
