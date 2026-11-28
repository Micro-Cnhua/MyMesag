package com.mymesag.notification

import android.content.Context
import android.util.Log
import com.mymesag.MyMesagApp
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.SentNotifyCache
import com.mymesag.data.followGroupTopicSubscription
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.NotificationEvent
import com.mymesag.model.SyncDoneEvent
import com.mymesag.model.SyncRequestEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * B 端补发响应：独占订阅 syncreq/{token} → 解密一次后分流：
 * 请求则从已发送内容缓存重放 ts>sinceTs 的通知（只加密给请求方，publish 到 notify 主题）并必发 SyncDone；
 * sinceTs<=0 的新加入设备不回放历史，只回一个当前时刻的水位；
 * 完成信号则通过 [doneEvents] 交给 A 端接收逻辑，避免同一信封被解密两次而触发抗重放误判。
 */
class SyncRequestHandler(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity,
    private val cache: SentNotifyCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _done = MutableSharedFlow<SyncDoneEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val doneEvents: SharedFlow<SyncDoneEvent> = _done.asSharedFlow()

    fun start() {
        scope.followGroupTopicSubscription(deviceIdentity, manager, { MqttTopics.syncReq(it) }, TAG)
        scope.launch {
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("syncreq/")) handle(msg.payload)
            }
        }
    }

    private suspend fun handle(payload: String) {
        val envelope = runCatching {
            json.decodeFromString(EncryptedEnvelope.serializer(), payload)
        }.getOrNull() ?: return
        val plaintext = CryptoManager.decrypt(envelope, MqttTopics.CH_SYNCREQ) ?: return
        // 请求与完成信号靠各自独有的必填字段区分，只解密一次
        val req = runCatching {
            json.decodeFromString(SyncRequestEvent.serializer(), plaintext)
        }.getOrNull()
        if (req != null && req.type == "syncreq") {
            handleRequest(req)
            return
        }
        val done = runCatching {
            json.decodeFromString(SyncDoneEvent.serializer(), plaintext)
        }.getOrNull()
        if (done != null && done.type == "syncdone") _done.tryEmit(done)
    }

    private suspend fun handleRequest(req: SyncRequestEvent) {
        val app = context.applicationContext as MyMesagApp
        val myId = deviceIdentity.deviceId()
        if (req.to != myId) return
        if (!app.appFilterRepository.syncSendEnabled.first()) {
            Log.d(TAG, "send disabled, skip sync request from=${req.from}")
            return
        }

        val groupToken = deviceIdentity.groupToken() ?: return
        val notifyTopic = MqttTopics.notify(groupToken)
        // sinceTs<=0 表示请求方还没有基线（新加入的设备）：只建水位，不回放历史。
        // upToTs 取本机时钟，与 NotificationEvent.ts 同时钟域；A 推进水位后断网重连仍走增量补发。
        val newPeer = req.sinceTs <= 0L
        val events = if (newPeer) emptyList() else cache.replaySince(req.sinceTs, REPLAY_LIMIT)
        var upToTs = if (newPeer) System.currentTimeMillis() else req.sinceTs
        for (e in events) {
            val plain = json.encodeToString(NotificationEvent.serializer(), e)
            val env = CryptoManager.encrypt(plain, myId, req.from, MqttTopics.CH_NOTIFY) ?: continue
            manager.publish(notifyTopic, json.encodeToString(EncryptedEnvelope.serializer(), env))
            if (e.ts > upToTs) upToTs = e.ts
        }

        // 必发完成信号（哪怕 0 条），让 A 停止重试并推进水位
        val done = SyncDoneEvent(
            id = UUID.randomUUID().toString(),
            from = myId,
            to = req.from,
            ts = System.currentTimeMillis(),
            replayed = events.size,
            upToTs = upToTs
        )
        val donePlain = json.encodeToString(SyncDoneEvent.serializer(), done)
        val doneEnv = CryptoManager.encrypt(donePlain, myId, req.from, MqttTopics.CH_SYNCREQ) ?: return
        manager.publish(
            MqttTopics.syncReq(groupToken),
            json.encodeToString(EncryptedEnvelope.serializer(), doneEnv)
        )
        Log.d(TAG, "sync replay done to=${req.from} count=${events.size} upToTs=$upToTs newPeer=$newPeer")
    }

    companion object {
        private const val TAG = "SyncReqHandler"
        private const val REPLAY_LIMIT = 500
    }
}