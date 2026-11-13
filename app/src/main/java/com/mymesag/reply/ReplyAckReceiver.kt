package com.mymesag.reply

import android.util.Log
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.followUserIdSubscription
import com.mymesag.model.AckEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * B 端回执接收：订阅 ack/{userId}/+，解析 A 端回传的明文 AckEvent 并广播给回复界面。
 * 订阅跟随 userId 变化（配对统一后无需重启）。回执按 replyId / replyToId 由 ViewModel 精确匹配。
 */
class ReplyAckReceiver(
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _acks = MutableSharedFlow<AckEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val acks: SharedFlow<AckEvent> = _acks.asSharedFlow()

    fun start() {
        scope.followUserIdSubscription(deviceIdentity, manager, { MqttTopics.ackWildcard(it) }, TAG)
        scope.launch {
            manager.messages.collect { msg ->
                if (!msg.topic.startsWith("ack/")) return@collect
                val ack = runCatching {
                    json.decodeFromString(AckEvent.serializer(), msg.payload)
                }.getOrNull() ?: return@collect
                Log.d(TAG, "ack reason=${ack.reason} replyId=${ack.replyId} to=${ack.to}")
                _acks.tryEmit(ack)
            }
        }
    }

    companion object {
        private const val TAG = "ReplyAckReceiver"
    }
}
