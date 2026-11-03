# V45 渠道健康度迁移注意事项

V45 为请求日志增加 health_indexed，并在 request_logs 上创建两个普通索引。PostgreSQL 的 ALTER TABLE 获取 ACCESS EXCLUSIVE 锁；Flyway 默认在事务中执行该迁移，锁会一直持有到整个迁移提交。索引构建期间，针对 request_logs 的并发查询和写入都可能被阻塞。

从 V44 或更早版本升级时，请安排维护窗口：停止网关实例和其他会访问 request_logs 的任务，启动新版本让 Flyway 完成迁移，确认启动就绪后再恢复流量。耗时取决于日志表大小与数据库负载，不要假设锁只持续到 ALTER TABLE 语句结束。

不要修改已经发布或执行过的 V45 脚本，否则已迁移数据库会遇到 Flyway checksum 不匹配。后续版本只能通过新的迁移脚本演进索引；这不能消除首次执行 V45 时的锁，因此维护窗口仍是安全升级方式。

ChannelHealthRepositoryTest.transactionalAlterAndIndexKeepTheAccessExclusiveLockUntilCommit 使用 PostgreSQL 验证事务中的列变更与索引创建期间，另一连接读取同一表会因锁超时而等待失败。
