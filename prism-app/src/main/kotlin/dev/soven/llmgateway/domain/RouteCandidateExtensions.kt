package dev.soven.llmgateway.domain

import dev.soven.llmgateway.channel.domain.ChannelKeyRecord
import dev.soven.llmgateway.routing.domain.candidate.RouteCandidate

/**
 * 数据面组件对自己消费的凭据类型做显式投影。
 *
 * 路由模块只认识渠道模块的 [dev.soven.llmgateway.channel.domain.UpstreamCredential]，
 * 不反向依赖订阅账号或静态密钥的业务字段；需要这些能力的调用方通过扩展属性取回具体类型。
 *
 * @author 文杰
 * @since 2026-11-09
 */
val RouteCandidate.channelKey: ChannelKeyRecord?
    get() = credential as? ChannelKeyRecord

val RouteCandidate.oauthAccount: OAuthAccountRecord?
    get() = credential as? OAuthAccountRecord