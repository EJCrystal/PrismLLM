# 镜像部署与更新

GitHub Actions 负责构建 Prism 的前端与后端，将完整运行镜像发布到 GHCR；服务器仅需 Docker Compose、部署配置和运行数据，不再需要源码、Maven 或 Node.js。

## 镜像发布

- `.github/workflows/publish-image.yml` 在本仓库 `main` 分支的 `CI` push 工作流成功后自动构建，不发布 PR 镜像，也不执行服务器部署。
- GitHub Actions 中可手动运行 `Publish GHCR image`，分支必须选择 `main`。手动发布也要求当前提交已有成功的 `main` push CI，不会绕过测试。过期提交会被跳过。
- 使用内置 `GITHUB_TOKEN` 登录 GHCR，写权限仅授予发布作业；无需配置长期推送令牌。组织策略或已有同名 package 必须允许本仓库 Actions 写入。
- 镜像名称自动使用小写仓库路径，本仓库为 `ghcr.io/ejcrystal/prismllm`；当前发布平台为 `linux/amd64`。
- 同时发布 `main` 与 `sha-<完整 40 位提交 SHA>` 标签。服务器默认使用 `main`，生产部署建议固定提交标签或镜像 digest，避免更新时版本不明确。

发布文件合入默认分支、对应 CI 和首次镜像构建完成前，镜像可能尚不存在。Actions 的发布结果只能说明镜像可拉取，不表示服务器已经更新。

## 私有镜像登录

镜像是否私有由 GHCR package 的可见性与访问权限决定，本工作流不会主动公开镜像。私有部署应保持 package 私有，并使用拥有该 package 读取权限的 GitHub 账号登录。

服务器使用 classic PAT，最小 scope 为 `read:packages`；不要将令牌放入 Compose、应用 `.env` 或仓库。将令牌安全地载入临时环境变量 `GHCR_TOKEN` 后执行：

~~~bash
printf '%s' "$GHCR_TOKEN" | docker login ghcr.io --username YOUR_GITHUB_LOGIN --password-stdin
unset GHCR_TOKEN
~~~

将 `YOUR_GITHUB_LOGIN` 替换为有权拉取镜像的账号。Docker 登录凭据也属于敏感数据，应限制服务器账号和 Docker 配置目录的访问权限。

## 新安装

部署目录保留 `docker-compose.yml`、填写完成的 `.env` 与 `plugins/` 即可。仅新安装时从 `.env.example` 生成 `.env`，并生成主密钥、设置管理员密码；已有部署不要覆盖原文件或重新生成主密钥。

~~~bash
mkdir -p plugins
docker compose pull
docker compose up -d --no-build
~~~

默认 Compose 仅引用镜像，不包含 `build:`，因此服务器不需要 Dockerfile 或源码。发布到其它仓库时，可在 `.env` 的 `PRISM_IMAGE` 中填写相应镜像地址。

## 保留现有数据更新

在原部署目录操作，保留现有 Compose override、端口、反向代理、出站代理和其它运行时配置；不要将本地开发用的 `docker-compose.build.yml` 加入服务器部署命令。若已有部署脚本执行源码构建，应改成下方拉取与重建 gateway 的步骤，保留原有备份和就绪检查。

更新前按[配置指南](configuration.md#生产部署与恢复边界)备份数据库及与之匹配的 `GATEWAY_MASTER_KEY`。需要固定版本时，仅修改现有 `.env` 中的 `PRISM_IMAGE`；例如 `ghcr.io/ejcrystal/prismllm:sha-<完整提交 SHA>`（替换占位符），也支持 `ghcr.io/ejcrystal/prismllm@sha256:<digest>`。

确认现有 PostgreSQL 正常运行后执行：

~~~bash
docker compose pull gateway
docker compose up -d --no-build --no-deps gateway
docker compose logs --tail=100 gateway
~~~

待启动完成后检查就绪状态；返回成功后再恢复流量：

~~~bash
docker compose exec -T gateway curl --fail --silent --show-error http://localhost:8080/health/ready
~~~

这些命令仅替换 gateway 容器，不重建 PostgreSQL 服务，不删除卷。默认配置保留：

| 对象 | 保持不变的配置 |
| --- | --- |
| Compose 项目 | `prism-llm` |
| PostgreSQL 数据卷 | `prism-llm_pgdata`，挂载到 `/var/lib/postgresql/data` |
| Docker 网络 | `prism-llm_default` |
| 应用配置与加密密钥 | 原部署目录的 `.env`，尤其是原 `GATEWAY_MASTER_KEY` |
| 外部插件 | 原 `./plugins` 目录，只读挂载到 `/app/plugins` |
| 停机预算 | `stop_grace_period: 60s`，已有自定义值继续保留 |

**不要执行 `docker compose down -v`、删除 `prism-llm_pgdata`、重新生成主密钥或用示例配置覆盖原 `.env`。** 私有仓库下载失败时先排查 GHCR 登录和服务器网络，不回退到现场构建，也不清理现有数据来重试。

保留数据卷不等于没有数据库升级：新版本启动仍会执行 Flyway 迁移。回退旧镜像前必须检查数据库兼容性；必要时在维护窗口恢复匹配的数据库备份与主密钥，不能假定修改镜像标签就能撤销表结构迁移。

## 本地源码构建

需要本地验证源码或构建尚未发布的版本时，额外指定构建覆盖文件：

~~~bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
~~~

此模式使用本地镜像 `prism-llm:local`，不尝试拉取私有 GHCR 镜像；数据库、网络和插件挂载与默认 Compose 一致。它仍使用同一个数据卷，切换运行版本前同样需要备份与检查迁移兼容性。
