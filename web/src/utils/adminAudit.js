const actionLabels = {
  'admin.login': '管理员登录',
  'admin.logout': '管理员退出',
  'admin.model_suggestions': '匹配参考模型',
  'admin.write': '其他管理操作',
  'account.avatar.update': '更新管理员头像',
  'account.password.update': '修改管理员密码',
  'channel.discover_models': '发现渠道模型',
  'channel.probe_protocols': '探测渠道协议',
  'channel.model_test': '测试渠道模型',
  'channel.create': '创建渠道',
  'channel.update': '更新渠道',
  'channel.delete': '删除渠道',
  'alias.create': '创建模型别名',
  'alias.update': '更新模型别名',
  'alias.delete': '删除模型别名',
  'client_key.create': '创建调用方密钥',
  'client_key.update': '更新调用方密钥',
  'client_key.delete': '删除调用方密钥',
  'client_key.rotate': '轮换调用方密钥',
  'client_key.revoke': '撤销调用方密钥',
  'model_price.create': '创建模型单价',
  'model_price.batch': '批量套用模型单价',
  'model_price.update': '更新模型单价',
  'model_price.delete': '删除模型单价',
  'model_default_price.create': '创建模型默认价',
  'model_default_price.update': '更新模型默认价',
  'model_default_price.delete': '删除模型默认价',
  'model_test_settings.update': '更新模型测试设置',
  'request_log.cleanup': '清理请求日志',
}

export function auditActionLabel(action) {
  return actionLabels[action] || action
}

export function auditResultLabel(result) {
  return result === 'success' ? '成功' : result === 'failure' ? '失败' : result
}

export function auditResultClass(result) {
  return result === 'success' ? 'success' : 'danger'
}
