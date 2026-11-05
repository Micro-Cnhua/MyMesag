package com.mymesag.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymesag.MyMesagApp
import com.mymesag.data.CryptoManager
import com.mymesag.data.MqttConnection
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.ReplyEvent
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

class ReplyViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyMesagApp
    private val json = Json { encodeDefaults = true }

    fun send(replyToId: String, pkg: String, targetDeviceId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val userId = app.deviceIdentity.userId()
            val deviceId = app.deviceIdentity.deviceId()
            val event = ReplyEvent(
                id = UUID.randomUUID().toString(),
                from = deviceId,
                to = targetDeviceId,
                ts = System.currentTimeMillis(),
                seq = System.currentTimeMillis(),
                replyToId = replyToId,
                pkg = pkg,
                text = text
            )
            val plaintext = json.encodeToString(ReplyEvent.serializer(), event)
            val envelope = CryptoManager.encrypt(plaintext, deviceId, targetDeviceId)
            if (envelope == null) {
                Log.w(TAG, "no session key for $targetDeviceId, cannot encrypt reply")
                return@launch
            }
            val payload = json.encodeToString(EncryptedEnvelope.serializer(), envelope)
            MqttConnection.manager.publish(MqttTopics.reply(userId, targetDeviceId), payload)
        }
    }

    companion object {
        private const val TAG = "ReplyVM"
    }
}
