package com.mymesag.reply

import android.content.Context
import android.util.Log
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.followGroupTopicSubscription
import com.mymesag.model.AckEvent
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.MarkReadEvent
import com.mymesag.model.ReplyResult
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A 端远程标记已读：订阅 markread/{token}/+ → 解密请求 → 触发缓存的源应用「标记已读」动作 → 回 ack。
 * 复用回复链路的 ReplyActionCache.getReadAction 与 ack 通道；动作失效/不存在时回相应原因码（尽力而为）。
 */
class MarkReadHandler(
    context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity,
    private val cache: ReplyActionCache
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true }

    fun start() {
        scope.followGroupTopicSubscription(deviceIdentity, manager, { MqttTopics.markReadWildcard(it) }, TAG)
        scope.launch {
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("markread/")) handle(msg.payload)
            }
        }
    }

    private suspend fun handle(payload: String) {
        val envelope = runCatching {
            json.decodeFromString(EncryptedEnvelope.serializer(), payload)
        }.getOrNull() ?: return
        val plaintext = CryptoManager.decrypt(envelope) ?: run {
            Log.w(TAG, "decrypt failed for markread")
            return
        }
        val event = runCatching {
            json.decodeFromString(MarkReadEvent.serializer(), plaintext)
        }.getOrNull() ?: return
        if (event.type != "markread") return
        val myId = deviceIdentity.deviceId()
        if (event.to != myId) return

        val readAction = cache.getReadAction(event.replyToId)
        if (readAction == null) {
            Log.d(TAG, "read action not found replyToId=${event.replyToId}")
            ack(event, myId, ReplyResult.NOT_FOUND)
            return
        }
        val pi = readAction.actionIntent
        if (pi == null) {
            ack(event, myId, ReplyResult.NO_READ_ACTION)
            return
        }
        try {
            pi.send(appContext, 0, null)
            Log.d(TAG, "marked read replyToId=${event.replyToId} pkg=${event.pkg}")
            ack(event, myId, ReplyResult.OK)
        } catch (e: Exception) {
            Log.w(TAG, "mark read failed: ${e.message}", e)
            ack(event, myId, ReplyResult.NO_READ_ACTION)
        }
    }

    private suspend fun ack(event: MarkReadEvent, myId: String, reason: String) {
        val groupToken = deviceIdentity.groupToken() ?: return
        val ack = AckEvent(
            id = UUID.randomUUID().toString(),
            from = myId,
            to = event.from,
            ts = System.currentTimeMillis(),
            replyToId = event.replyToId,
            reason = reason,
            replyId = event.id
        )
        val envelope = CryptoManager.encrypt(
            json.encodeToString(AckEvent.serializer(), ack), myId, event.from
        ) ?: return
        manager.publish(
            MqttTopics.ack(groupToken, event.from),
            json.encodeToString(EncryptedEnvelope.serializer(), envelope)
        )
        Log.d(TAG, "markread ack reason=$reason to=${event.from}")
    }

    companion object {
        private const val TAG = "MarkReadHandler"
    }
}