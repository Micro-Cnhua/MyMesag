package com.mymesag.reply

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.followUserIdSubscription
import com.mymesag.model.AckEvent
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.ReplyEvent
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
 * A 端回复处理（阶段 4 / 项目书 9.3）：
 * 订阅 reply/{userId}/+ → 找到 RemoteInput Action → 执行回复 → 回传 ack（原因码，表 9）。
 */
class ReplyHandler(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity,
    private val cache: ReplyActionCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = true }

    fun start() {
        scope.followUserIdSubscription(deviceIdentity, manager, { MqttTopics.replyWildcard(it) }, TAG)
        scope.launch {
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("reply/")) handleReply(msg.payload)
            }
        }
    }

    private suspend fun handleReply(payload: String) {
        val envelope = try {
            json.decodeFromString(EncryptedEnvelope.serializer(), payload)
        } catch (e: Exception) {
            Log.e(TAG, "decode envelope error: ${e.message}")
            return
        }
        val plaintext = CryptoManager.decrypt(envelope) ?: run {
            Log.w(TAG, "decrypt failed for reply")
            return
        }
        val event = try {
            json.decodeFromString(ReplyEvent.serializer(), plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "decode error: ${e.message}")
            return
        }
        val myId = deviceIdentity.deviceId()
        if (event.to != myId) {
            Log.d(TAG, "skip reply for other device to=${event.to}")
            return
        }

        val action = cache.get(event.replyToId)
        if (action == null) {
            Log.d(TAG, "action not found replyToId=${event.replyToId}")
            ack(event, myId, ReplyResult.NOT_FOUND)
            return
        }
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) {
            Log.d(TAG, "no remote input replyToId=${event.replyToId}")
            ack(event, myId, ReplyResult.NO_REPLY_ACTION)
            return
        }

        try {
            val intent = Intent()
            val bundle = Bundle()
            remoteInputs.forEach { bundle.putCharSequence(it.resultKey, event.text) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            action.actionIntent.send(context, 0, intent)
            Log.d(TAG, "reply sent replyToId=${event.replyToId} pkg=${event.pkg}")
            ack(event, myId, ReplyResult.OK)
        } catch (e: Exception) {
            Log.w(TAG, "reply failed: ${e.message}", e)
            ack(event, myId, ReplyResult.NO_REPLY_ACTION)
        }
    }

    private suspend fun ack(event: ReplyEvent, myId: String, reason: String) {
        val userId = deviceIdentity.userId()
        val ack = AckEvent(
            id = UUID.randomUUID().toString(),
            from = myId,
            to = event.from,
            ts = System.currentTimeMillis(),
            replyToId = event.replyToId,
            reason = reason,
            replyId = event.id
        )
        val payload = json.encodeToString(AckEvent.serializer(), ack)
        manager.publish(MqttTopics.ack(userId, event.from), payload)
        Log.d(TAG, "ack reason=$reason to=${event.from}")
    }

    companion object {
        private const val TAG = "ReplyHandler"
    }
}
