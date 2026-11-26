# 订阅账号隔离 UI 夹具

在仓库根目录启动（使用现有前端依赖，不需要后台）：

```bash
node web/test/fixtures/oauth-accounts/server.js 43197
```

- 亮色：<http://127.0.0.1:43197/admin/test/fixtures/oauth-accounts/index.html>
- 深色：<http://127.0.0.1:43197/admin/test/fixtures/oauth-accounts/index.html?theme=dark>
- HMR 已关闭；修改组件或样式后手动刷新页面。`Ctrl+C` 停止服务器。

提供商列表使用真实 `ChannelsView`：在地址后加 `?view=providers`。依次点击「接入提供商 → 官方订阅 → Antigravity → 继续」，确认只打开新建弹窗；点击取消、右上角关闭、按 Escape 或点击遮罩，都应直接返回提供商列表，不显示账号池。新建加载期间也应能取消，延迟响应不得重新打开弹窗。

OpenAI / ChatGPT 和 Antigravity 都需要检查顶层「接入提供商」和列表「添加账号」两个入口。使用 `?view=providers&scenario=empty-provider&provider=chatgpt` 模拟尚未置备且没有账号的 OpenAI 提供商；`&provider=antigravity` 切换提供商，`&provisioned=true` 保留已置备的渠道但清空账号。桌面行和手机卡片上的「添加账号」均应只打开独立新建窗，取消后直接返回提供商列表。已有账号时「管理账号」仍正常打开账号池，池内新增取消不退出账号池。

提供商账号列表使用同一隔离数据和真实 `OAuthProviderDrawer`：在地址后加 `?view=provider`。加上 `&autoCreate=true` 可复现从账号池入口自动打开新建订阅账号、取消后保留原账号池的流程；`&provider=chatgpt` 切换到 OpenAI 订阅，`&theme=dark` 切换深色。检查 1440px 下六列完整展示、窄窗口下滚动提示、手机端操作列不遮挡刷新时间，以及标题启停开关的样式。

入口按视图挂载实际 `ChannelsView` 或 `OAuthAccountsView`，不挂载 `App` 或执行认证引导。提供商列表的价格、健康度、占用和审计请求也只返回固定合成读数；查询字符串按完整路由白名单匹配。九个合成账号覆盖多组额度、80%/91%/100% 消耗、读数未知/失败、停用且授权失效、冷却，以及账号阻断但没有额度读数。邮箱使用 `example.invalid`，没有 token、真实账号、代理或上游 URL。

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
