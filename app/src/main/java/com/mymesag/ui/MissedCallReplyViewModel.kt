package com.mymesag.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymesag.MyMesagApp
import com.mymesag.data.MqttConnection
import com.mymesag.model.MissedCallReplyEvent
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

class MissedCallReplyViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyMesagApp
    private val json = Json { encodeDefaults = true }

    fun send(phoneNumber: String, contactName: String, targetDeviceId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val userId = app.deviceIdentity.userId()
            val deviceId = app.deviceIdentity.deviceId()
            val event = MissedCallReplyEvent(
                id = UUID.randomUUID().toString(),
                from = deviceId,
                to = targetDeviceId,
                ts = System.currentTimeMillis(),
                phoneNumber = phoneNumber,
                contactName = contactName,
                text = text
            )
            val payload = json.encodeToString(MissedCallReplyEvent.serializer(), event)
            MqttConnection.manager.publish(
                MqttTopics.missedCallReply(userId, targetDeviceId),
                payload
            )
        }
    }
}
