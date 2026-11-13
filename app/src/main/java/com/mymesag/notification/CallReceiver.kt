package com.mymesag.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mymesag.R
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.followUserIdSubscription
import com.mymesag.model.CallEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * B 端来电显示（阶段 5 / 项目书 10.2）：
 * 订阅 call/{userId}/+ → 显示「来电」通知（不提供任何接听/挂断按钮，号码为空时降级文案）。
 */
class CallReceiver(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        scope.followUserIdSubscription(deviceIdentity, manager, { MqttTopics.callWildcard(it) }, TAG)
        scope.launch {
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("call/")) handleCall(msg.payload)
            }
        }
    }

    private suspend fun handleCall(payload: String) {
        val event = try {
            json.decodeFromString(CallEvent.serializer(), payload)
        } catch (e: Exception) {
            Log.e(TAG, "decode error: ${e.message}")
            return
        }
        val myId = deviceIdentity.deviceId()
        if (event.from == myId) {
            Log.d(TAG, "skip self call from=${event.from}")
            return
        }
        if (event.state == "ended") {
            dismissCallNotification(event)
            return
        }
        if (event.state != "ringing") return // 阶段 5 只处理响铃
        showCallNotification(event)
    }

    private fun dismissCallNotification(event: CallEvent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(CALL_TAG, event.id.hashCode())
        Log.d(TAG, "call ended, dismiss notification id=${event.id}")
    }

    private fun showCallNotification(event: CallEvent) {
        ensureChannel()
        val nm = context.getSystemService(NotificationManager::class.java)
        val name = event.contactName ?: "未知号码"
        val content = if (event.number != null) {
            "$name（${event.number}）正在呼叫"
        } else {
            "$name 正在呼叫（号码不可用）"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("来电")
            .setContentText(content)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(CALL_TAG, event.id.hashCode(), notification)
            Log.d(TAG, "call notification number=${event.number} contact=${event.contactName}")
        } catch (e: SecurityException) {
            Log.w(TAG, "notification denied", e)
        }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "来电提醒", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CALL_TAG = "mymesag_call"
        const val CHANNEL_ID = "call_notifications"
        private const val TAG = "CallReceiver"
    }
}
