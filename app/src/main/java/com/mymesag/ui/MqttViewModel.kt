package com.mymesag.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymesag.MyMesagApp
import com.mymesag.data.MqttConnection
import com.mymesag.network.MqttConfig
import kotlinx.coroutines.launch
import java.util.UUID

class MqttViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyMesagApp
    private val manager = MqttConnection.manager
    val state = manager.state

    private val _log = mutableStateListOf<String>()
    val log: List<String> = _log

    var brokerUrl by mutableStateOf("")
    var clientId by mutableStateOf(newClientId())
    var deviceTag by mutableStateOf("")
    var deviceTagError by mutableStateOf<String?>(null)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var caCertPem by mutableStateOf("")
    var subscribeTopic by mutableStateOf("test/mymesag")
    var publishTopic by mutableStateOf("test/mymesag")
    var publishPayload by mutableStateOf("Hello from MyMesag")

    init {
        viewModelScope.launch {
            app.mqttConfigStore.savedDeviceTag()?.let { deviceTag = it }
        }
        viewModelScope.launch {
            manager.messages.collect { msg ->
                _log.add(0, "[${msg.topic}] ${msg.payload}")
            }
        }
    }

    fun connect() {
        val broker = brokerUrl.trim()
        val cid = clientId.trim().ifBlank { newClientId() }
        val tag = deviceTag.trim()
        if (tag.isNotEmpty() && !DEVICE_TAG_REGEX.matches(tag)) {
            deviceTagError = "设备标签须为不超过 8 位英文或数字"
            return
        }
        deviceTagError = null
        val user = username.trim().ifBlank { null }
        val pass = password.ifBlank { null }
        val ca = caCertPem.trim().ifBlank { null }
        _log.add(0, "→ 连接 $broker")
        viewModelScope.launch {
            app.mqttConfigStore.save(broker, cid, user, pass, ca, tag)
            val willTopic = app.presenceManager.willTopic()
            manager.connect(
                MqttConfig(
                    brokerUrl = broker, clientId = cid, username = user, password = pass,
                    willTopic = willTopic, willPayload = "offline", caCertPem = ca
                )
            )
        }
    }

    fun disconnect() {
        _log.add(0, "→ 断开连接")
        manager.disconnect()
    }

    fun subscribe() {
        val t = subscribeTopic.trim()
        if (t.isNotEmpty()) {
            _log.add(0, "→ 订阅 $t")
            manager.subscribe(t)
        }
    }

    fun publish() {
        val t = publishTopic.trim()
        if (t.isNotEmpty()) {
            _log.add(0, "→ 发布 $t : $publishPayload")
            manager.publish(t, publishPayload)
        }
    }

    private fun newClientId() = "MyMesag-" + UUID.randomUUID().toString().take(8)

    companion object {
        private val DEVICE_TAG_REGEX = Regex("^[A-Za-z0-9]{1,8}$")
    }
}
