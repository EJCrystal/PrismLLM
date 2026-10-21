# 使用 Kotlin + Ktor 构建独立 LLM 网关

目标是内部团队真实流量可用的 LLM 网关。团队长期使用 Kotlin/Java，Rust 没有生产经验；这个负载是 I/O 密集的长连接代理，CPU 不是瓶颈。决定使用 Kotlin 2.3 + Ktor 3.6 新建独立仓库，不扩展 `components/gateway-service`，也不 fork axonhub。

## Considered Options

- **Rust + axum**：单二进制、低内存，但团队从零学习，交付周期按 2–3 倍估；性能优势在当前规模用不上。
- **Spring Boot + WebFlux**：可复用公司组件，但响应式操作符与背压调试成本高，独立项目没有复用刚需。
- **fork axonhub**：上线最快，但不是“从零写”，且后续与上游演进绑定。

## Consequences

JVM 常驻内存高于 Rust；单实例部署下这不是约束。后续若需要边缘部署或单二进制，可把协议转换与计量模块抽成独立 Rust 服务，而不是重写整个网关。
