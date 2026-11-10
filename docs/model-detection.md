# 模型检测

管理台的「模型检测」页把三种互补的探针放在同一个入口：

| 页签 | 回答的问题 | 是否写入请求日志 |
| --- | --- | --- |
| 连通性检测 | 渠道、凭据与协议现在能不能调通？ | 否 |
| 智商检测 | 模型的推理、视觉与指令遵循表现如何？ | 否 |
| 指纹检测 | 这条回答更像参考库里的哪个模型？ | 否 |

三类检测都通过渠道已保存的上游凭据直接请求上游，不经过数据面，也不消耗调用方密钥额度。
检测结果只代表本次请求，不替代持续的健康检查或线上流量观测。

## 连通性检测

选择渠道与已授权模型后，可指定使用哪条凭据、走哪个协议，并修改本次提示词。请求默认提示词来自
「系统配置 → 模型测试」，超时 45 秒；结果展示 HTTP 状态、耗时、上游声明的模型名、token 用量和
完整响应文本。失败信息直接来自上游，便于排查鉴权、地址和协议配置。

- 页面接口：`GET /admin/api/model-tests/targets`
- 运行接口：`POST /admin/api/channels/<channel-id>/model-test`
- 订阅账号渠道同样支持从账号凭据发起测试，使用 `/admin/api/oauth-accounts/<id>/model-test`。

## 智商检测

包含鹈鹕骑行与糖果测试两道内置题，支持可插拔判分器和人工复判，题库可自定义。完整说明见
[`docs/iq-test.md`](./iq-test.md)。这一页仍使用原有的 `/admin/api/iq-tests/*` 接口。

## 指纹检测

### 原理

指纹检测基于开源项目 [lm-detector](https://github.com/Ikaleio/lm-detector)（MIT）的算法与参考库：

1. 每次运行生成三道不同的随机数题，要求模型只输出整数，数量在 292–332 之间，取值 1–355。
2. 三条请求并行发出，沿用 lm-detector 的三样本采样方式；协议、凭据和提示词由网关按渠道配置发送。
3. 网关在本地解析回答，统计越界值、有效整数数量，并抽取计数分布、顺序块、位置核与序列形状等特征。
4. 特征与随包发布的 `shared_detector.bin` 参考库比对，输出闭集内 68 个候选模型的分数与相对概率。

参考库、题面模板和检测器都打包在 `prism-app` 资源中，运行时不联网，也不调用外部判分服务。

### 判定结果

| decision | 含义 |
| --- | --- |
| `scored` | 三条回答全部有效，完成完整排名 |
| `partial` | 只有一部分回答达到有效整数数量，排名基于可用样本 |
| `not_model` | 回答的数字序列不像语言模型输出（例如随机数生成器或固定模板） |
| `unscorable` | 没有足够的有效回答，无法排名 |

概率是参考库内的校准相对值，不是“有百分之几的概率由某模型生成”的绝对结论。结果同时给出一致性说明
（evidence）和序列诊断，用于区分“证据较弱”与“完全不像模型”。检测不能证明真实部署模型，也不应作为
封禁、计费或合规判断的唯一依据。

### 接口

- 可测试目标：`GET /admin/api/model-tests/targets`（与连通性检测共用）
- 参考库信息：`GET /admin/api/model-tests/fingerprint/info`
- 运行检测：`POST /admin/api/model-tests/fingerprint`
- 历史与详情：`GET /admin/api/model-tests/fingerprint?limit=20`、`GET /admin/api/model-tests/fingerprint/<id>`

```bash
curl -b cookies.txt -X POST http://localhost:8080/admin/api/model-tests/fingerprint \
  -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
  -d '{"channelId":"<channel-id>","model":"claude-haiku-4.5","protocol":"messages"}'
```

`keyId` 与 `protocol` 可省略；省略 `keyId` 时按渠道凭据优先级选择，省略 `protocol` 时使用渠道的首选协议。
单次运行最长等待 4 分钟，单条回答最多返回 50000 字符，输出上限为 8192 token。

### 存储与限制

- 结果保存在 `fingerprint_test_runs` 表，保留最近 100 次；列表接口最多返回 50 条。
- 每条记录保存完整排名快照与三条原始回答；单条回答最多保存 20000 字符。
- 参考库加载失败时，指纹接口返回 503，连通性与智商检测不受影响。
- 参考库由 lm-detector 的数据构建；升级参考库需要替换 `shared_detector.bin` 与 `models.json`，并同步
  `detector_sha256` 校验值。许可证见 `prism-app/src/main/resources/fingerprint/LICENSE`。

## 相关文件

- 后端路由与持久化：`prism-app/src/main/kotlin/dev/soven/prismllm/api/AdminRoutes.kt`、
  `prism-admin/src/main/kotlin/dev/soven/prismllm/admin/persistence/FingerprintTestRunRepository.kt`
- 检测器实现：`prism-app/src/main/kotlin/dev/soven/prismllm/proxy/fingerprint/`
- 前端页面：`web/src/components/ModelTestView.vue`（页签容器）、`web/src/components/ModelConnectivityView.vue`、
  `web/src/components/FingerprintTestView.vue`