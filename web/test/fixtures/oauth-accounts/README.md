# 订阅账号隔离 UI 夹具

在仓库根目录启动（使用现有前端依赖，不需要后台）：

```bash
node web/test/fixtures/oauth-accounts/server.js 43197
```

- 亮色：<http://127.0.0.1:43197/admin/test/fixtures/oauth-accounts/index.html>
- 深色：<http://127.0.0.1:43197/admin/test/fixtures/oauth-accounts/index.html?theme=dark>
- HMR 已关闭；修改组件或样式后手动刷新页面。`Ctrl+C` 停止服务器。

入口挂载实际 `OAuthAccountsView`，不挂载 `App` 或执行认证引导。九个合成账号覆盖多组额度、80%/91%/100% 消耗、读数未知/失败、停用且授权失效、冷却，以及账号阻断但没有额度读数。邮箱使用 `example.invalid`，没有 token、真实账号、代理或上游 URL。

`fake-fetch.js` 在组件导入前安装：所有请求仅使用内存数据，未匹配请求拒绝，绝不回退到原生 fetch。启停、刷新、设置和删除只改变当前页面的夹具；重载恢复。OAuth 登录及真实模型测试接口不在白名单中。

静态服务器只监听 loopback；关闭 `.env`、默认 Vite 配置、后端代理和公共资源目录，拒绝后台 API 和白名单外文件。生产入口不引用夹具，正常 Vite 构建不包含这些文件。

浏览器保留原生时钟，合成期限相对页面加载时刻生成，避免破坏 Vue/自动化事件的时间戳判断。DOM 回归直接使用 `createOAuthAccountsFixture(FIXTURE_NOW)` 保持确定性。

对应测试：

```bash
npm --prefix web test
npm --prefix web run typecheck
npm --prefix web run build
```

`oauthAccount.test.js` 验证纯函数边界；`oauthAccountsView.test.js` 挂载真实页面及菜单；`actionMenu.test.js` 验证键盘、分组、禁用项、首次真实高度测量和动态项目重定位。视觉检查使用 1440×900、1024×768、390×844，分别验收亮色和深色主题。
