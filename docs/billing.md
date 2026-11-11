# PrismLLM 单请求精确计费与用量核算

PrismLLM 在请求日志异步写入时，基于真实的 Token 用量与配置单价，实时计算并快照单条请求的费用。

---

## 一、计费规则与计算公式

### 1. Token 费用计算公式

$$
\begin{aligned}
\text{费用} = &\ \frac{\text{未缓存输入 Token} \times \text{输入单价}}{1,000,000} \\
&+ \frac{\text{缓存读取 Token} \times \text{缓存读取单价}}{1,000,000} \\
&+ \frac{\text{缓存写入 Token} \times \text{缓存写入单价}}{1,000,000} \\
&+ \frac{\text{输出 Token} \times \text{输出单价}}{1,000,000}
\end{aligned}
$$

其中：$\text{未缓存输入 Token} = \text{总输入 Token} - \text{缓存读取 Token} - \text{缓存写入 Token}$。

- **单价基数**：所有单价均按**每百万 Token（Per 1M Tokens）**进行配置；
- **精度**：费用计算保留 8 位小数（四舍五入），避免微小调用的舍入误差；
- **单价回退规则**：
  - 缓存读取单价（`cachedInputPricePerMillion`）留空时，自动回退沿用基础输入单价；
  - 缓存写入单价（`cachedInputWritePricePerMillion`）留空时，同样自动回退沿用基础输入单价；
- **推理 Token**：对于带思考过程的模型（如 DeepSeek-R1、o3-mini），推理 Token 已包含在输出 Token 计数中，不重复加倍计费；
- **缺失处理**：上游响应缺少用量数据（Usage）或模型未定价时，日志显示为“未配置”或“—”，**绝不默认记为 0 费用**。

---

## 二、双层价格继承模型

单价分为两层，解析优先级如下：

$$\text{渠道覆盖价} > \text{模型默认价} > \text{未定价}$$

### 1. 渠道覆盖价（Channel Specific Price）
- 为指定渠道的特定上游模型设置；
- 适用于上游有特殊折扣、包月额度或特殊费率的渠道；
- 接口：`GET/POST /admin/api/model-prices`。

### 2. 模型默认价（Default Model Price）
- 依据**对外模型 ID（Canonical Model ID）**配置全局默认基准价；
- 当渠道没有单独配置覆盖价时，自动继承该默认单价；
- 维护一份模型默认价即可覆盖该模型跨所有渠道的调用基线；
- 接口：`GET/POST /admin/api/model-default-prices`。

---

## 三、计费币种

- **统一 USD 计费**：所有单价、费用与限额均以 `USD` 计，控制台不再提供币种切换；
- **无隐含汇率换算**：网关不做任何汇率换算；
- **参考单价**：控制台提供从外部目录获取参考价格的功能，直接采用 USD 原始值。

---

## 四、用量报表（Usage Reports）

管理台提供强大的交互式多维度报表：
- **报表接口**：`GET /admin/api/usage-report?from=<epoch-ms>&to=<epoch-ms>`；
- **灵活筛选**：可按调用方密钥、渠道、模型、协议类型与时区进行过滤聚合；
- **数据导出**：支持一键导出为包含 USD 费用列的 CSV 表格。
