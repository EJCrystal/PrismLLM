# 上游调用使用 Java HttpClient

网关需要在上游首 token 超时或调用方断开时真正终止上游连接，否则慢上游会继续占用连接和额度。Ktor 的 CIO 客户端在协程取消后仍可能继续读取响应，实测无法可靠断开上游。因此上游 HTTP 客户端使用 Ktor 的 Java 引擎（底层为 JDK `HttpClient`），而不是 CIO。

## Considered Options

- **CIO**：与 Ktor 协程模型一致，但取消语义不满足网关的超时和断连要求。
- **OkHttp**：取消能力成熟，但需要额外依赖和线程池配置；Java 引擎已随 JDK 21 提供。
- **Java HttpClient**：取消会关闭底层连接，依赖最少，已验证首 token 超时能触发假上游取消。

## Consequences

上游连接行为依赖 JDK HTTP 客户端，排障时需要同时看 Ktor 与 JDK 的异常链。连接超时、首 token 超时和总超时仍由网关统一控制；更换引擎前必须保留“超时后上游收到取消”的集成测试。
