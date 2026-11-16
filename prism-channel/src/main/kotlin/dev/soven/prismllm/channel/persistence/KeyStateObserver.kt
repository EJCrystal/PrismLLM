package dev.soven.prismllm.channel.persistence

import dev.soven.prismllm.channel.domain.key.ChannelKeyState

/** 在状态更新的事务内调用；实现只能写入本地事务，不能执行网络请求。 */
fun interface KeyStateObserver {
    fun changed(change: KeyStateChange)
}

data class KeyStateChange(
    val channelId: String,
    val channelName: String,
    val keyId: String,
    val keyName: String,
    val previousState: ChannelKeyState,
    val state: ChannelKeyState,
    val reasonCode: String,
    val occurredAt: Long,
)
