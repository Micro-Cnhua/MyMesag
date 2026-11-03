package com.mymesag.reply

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mymesag.data.DeviceIdentity
import com.mymesag.model.MissedCallReplyEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MissedCallReplyHandler(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        scope.launch {
            try {
                val userId = deviceIdentity.userId()
                val topic = MqttTopics.missedCallReplyWildcard(userId)
                Log.d(TAG, "subscribe $topic")
                manager.subscribe(topic)
            } catch (e: Exception) {
                Log.e(TAG, "subscribe error", e)
            }
        }
        scope.launch {
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("missedcall/")) handleReply(msg.payload)
            }
        }
    }

    private suspend fun handleReply(payload: String) {
        val event = try {
            json.decodeFromString(MissedCallReplyEvent.serializer(), payload)
        } catch (e: Exception) {
            Log.e(TAG, "decode error: ${e.message}")
            return
        }
        val myId = deviceIdentity.deviceId()
        if (event.to != myId) {
            Log.d(TAG, "skip reply for other device to=${event.to}")
            return
        }
        val number = event.phoneNumber
        if (number.isBlank()) {
            Log.w(TAG, "no phone number in missed call reply")
            return
        }
        sendSms(number, event.text)
    }

    private fun sendSms(number: String, message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS permission not granted")
            return
        }
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            Log.i(TAG, "SMS sent to $number (${message.length} chars)")
        } catch (e: Exception) {
            Log.e(TAG, "send SMS failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "MissedCallReply"
    }
}
