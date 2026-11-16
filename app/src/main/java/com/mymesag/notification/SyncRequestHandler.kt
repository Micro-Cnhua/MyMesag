package com.mymesag.notification

import android.content.Context
import android.util.Log
import com.mymesag.MyMesagApp
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.SentNotifyCache
import com.mymesag.data.followUserIdSubscription
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.NotificationEvent
import com.mymesag.model.SyncDoneEvent
import com.mymesag.model.SyncRequestEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * B 端补发响应：订阅 syncreq/{userId}/+ → 解密请求 → 从已发送内容缓存重放 ts>sinceTs 的通知
 * （只加密给请求方，publish 到 notify 主题，复用 A 端现有接收）→ 必发 SyncDone 让 A 停止重试。
 */
class SyncRequestHandler(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity,
    private val cache: SentNotifyCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun start() {
        scope.followUserIdSubscription(deviceIdentity, manager, { MqttTopics.syncReqWildcard(it) }, TAG)
        scope.launch {
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("syncreq/")) handle(msg.payload)
            }
        }
    }

    private suspend fun handle(payload: String) {
        val app = context.applicationContext as MyMesagApp
        val envelope = runCatching {
            json.decodeFromString(EncryptedEnvelope.serializer(), payload)
        }.getOrNull() ?: return
        val plaintext = CryptoManager.decrypt(envelope) ?: return
        // syncreq 通道双向：B 只处理请求；SyncDone 缺少 sinceTs，解码为请求会失败而被忽略
        val req = runCatching {
            json.decodeFromString(SyncRequestEvent.serializer(), plaintext)
        }.getOrNull() ?: return
        if (req.type != "syncreq") return
        val myId = deviceIdentity.deviceId()
        if (req.to != myId) return
        if (!app.appFilterRepository.syncSendEnabled.first()) {
            Log.d(TAG, "send disabled, skip sync request from=${req.from}")
            return
        }

        val userId = deviceIdentity.userId()
        val events = cache.replaySince(req.sinceTs, REPLAY_LIMIT)
        val notifyTopic = MqttTopics.notify(userId, myId)
        var upToTs = req.sinceTs
        for (e in events) {
            val plain = json.encodeToString(NotificationEvent.serializer(), e)
            val env = CryptoManager.encrypt(plain, myId, req.from) ?: continue
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
        val doneEnv = CryptoManager.encrypt(donePlain, myId, req.from) ?: return
        manager.publish(
            MqttTopics.syncReq(userId, req.from),
            json.encodeToString(EncryptedEnvelope.serializer(), doneEnv)
        )
        Log.d(TAG, "sync replay done to=${req.from} count=${events.size} upToTs=$upToTs")
    }

    companion object {
        private const val TAG = "SyncReqHandler"
        private const val REPLAY_LIMIT = 500
    }
}