package dev.soven.prismllm.proxy

import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.BaseApplicationResponse
import io.ktor.server.netty.NettyApplicationCall
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Ktor 的 handler/ResponseSent 完成早于 socket 写入；只以本响应末帧的 promise 结束 Drain。 */
internal suspend fun RequestDrain.Lease.trackResponse(call: ApplicationCall) {
    val response = call.response as? BaseApplicationResponse
        ?: call.attributes[BaseApplicationResponse.EngineResponseAttributeKey]
    // 测试宿主等非 Netty 引擎没有该输出管道，使用请求终结边界。
    val engineCall = response.call as? NettyApplicationCall ?: return
    val context = engineCall.context
    awaitResponse()
    var installed = false
    try {
        withContext(context.executor().asCoroutineDispatcher()) {
            if (!context.channel().isActive) {
                responseCompleted()
            } else {
                context.pipeline().addBefore(context.name(), null, ResponseWriteTracker(
                    ownsHeader = { message ->
                        try {
                            message === engineCall.response.responseMessage
                        } catch (_: UninitializedPropertyAccessException) {
                            false
                        }
                    },
                    onCompleted = ::responseCompleted,
                ))
                installed = true
            }
        }
    } finally {
        // 安装后即使交付被取消，也必须等写入或连接关闭，不能把取消当成成功输出。
        if (!installed) responseCompleted()
    }
}

private class ResponseWriteTracker(
    private val ownsHeader: (Any) -> Boolean,
    private val onCompleted: () -> Unit,
) : ChannelDuplexHandler() {
    private var selected = false
    private var terminalSeen = false
    private var done = false

    override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
        var completion = promise
        if (!done && !terminalSeen && (selected || ownsHeader(message))) {
            selected = true
            val terminal = message is FullHttpResponse || message is LastHttpContent
            // 先停止匹配，再等 promise；流水线后续响应不能完成当前请求。
            if (terminal) {
                terminalSeen = true
                completion = promise.unvoid()
                completion.addListener { complete(context) }
            }
        }
        context.write(message, completion)
    }

    override fun channelInactive(context: ChannelHandlerContext) {
        complete(context)
        context.fireChannelInactive()
    }

    private fun complete(context: ChannelHandlerContext) {
        if (done) return
        done = true
        onCompleted()
        if (context.pipeline().context(this) != null) context.pipeline().remove(this)
    }
}
