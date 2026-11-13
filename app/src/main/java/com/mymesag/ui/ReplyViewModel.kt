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
import com.mymesag.model.ReplyResult
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/** 回复界面的真实结果状态（由 A 端回执驱动，替代原先无条件的"已发送"）。 */
sealed interface ReplyUiState {
    object Idle : ReplyUiState
    object Sending : ReplyUiState
    object Success : ReplyUiState
    data class Failed(val reason: String, val message: String) : ReplyUiState
}

class ReplyViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyMesagApp
    private val json = Json { encodeDefaults = true }

    private val _state = MutableStateFlow<ReplyUiState>(ReplyUiState.Idle)
    val state: StateFlow<ReplyUiState> = _state.asStateFlow()

    private var pendingReplyId: String? = null
    private var pendingReplyToId: String? = null
    private var pendingTarget: String? = null
    private var timeoutJob: Job? = null
    private var myDeviceId: String? = null

    init {
        viewModelScope.launch {
            myDeviceId = app.deviceIdentity.deviceId()
            app.replyAckReceiver.acks.collect { ack ->
                val pid = pendingReplyId ?: return@collect
                if (ack.to != myDeviceId) return@collect
                val matches = ack.replyId == pid ||
                    (ack.replyId.isEmpty() && ack.replyToId == pendingReplyToId && ack.from == pendingTarget)
                if (!matches) return@collect
                timeoutJob?.cancel()
                pendingReplyId = null
                _state.value = if (ack.reason == ReplyResult.OK) {
                    ReplyUiState.Success
                } else {
                    ReplyUiState.Failed(ack.reason, reasonText(ack.reason))
                }
                Log.d(TAG, "ack matched replyId=$pid reason=${ack.reason}")
            }
        }
    }

    fun send(replyToId: String, pkg: String, targetDeviceId: String, text: String) {
        if (text.isBlank()) return
        if (_state.value is ReplyUiState.Sending) return
        viewModelScope.launch {
            if (!MqttConnection.manager.isConnected) {
                _state.value = ReplyUiState.Failed("NOT_CONNECTED", "本机未连接 MQTT 服务器，无法发送回复")
                return@launch
            }
            val userId = app.deviceIdentity.userId()
            val deviceId = app.deviceIdentity.deviceId()
            val replyId = UUID.randomUUID().toString()
            val event = ReplyEvent(
                id = replyId,
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
                _state.value = ReplyUiState.Failed("NO_KEY", "本机缺少与对方的会话密钥，请重新配对后再回复")
                return@launch
            }
            pendingReplyId = replyId
            pendingReplyToId = replyToId
            pendingTarget = targetDeviceId
            _state.value = ReplyUiState.Sending
            val payload = json.encodeToString(EncryptedEnvelope.serializer(), envelope)
            MqttConnection.manager.publish(MqttTopics.reply(userId, targetDeviceId), payload)
            timeoutJob?.cancel()
            timeoutJob = viewModelScope.launch {
                delay(ACK_TIMEOUT_MS)
                if (pendingReplyId == replyId) {
                    pendingReplyId = null
                    _state.value = ReplyUiState.Failed(
                        ReplyResult.OFFLINE_TIMEOUT,
                        "未收到对方回执，对方可能离线或未连接"
                    )
                }
            }
        }
    }

    private fun reasonText(reason: String): String = when (reason) {
        ReplyResult.NOT_FOUND -> "对方未找到该通知的回复入口（通知可能已被清除或过期）"
        ReplyResult.NO_REPLY_ACTION -> "对方应用没有可用的回复动作，或系统拒绝了回复调用"
        ReplyResult.OFFLINE_TIMEOUT -> "未收到对方回执，对方可能离线"
        ReplyResult.DECRYPT_FAILED -> "对方解密失败，密钥可能不一致，请重新配对"
        ReplyResult.SEQ_REPLAY -> "对方判定为重放消息，已拒绝"
        ReplyResult.NOT_PAIRED -> "双方未配对或配对已失效"
        ReplyResult.UNSUPPORTED_VERSION -> "对方版本不支持该回复"
        else -> "回复失败（$reason）"
    }

    companion object {
        private const val TAG = "ReplyVM"
        private const val ACK_TIMEOUT_MS = 15_000L
    }
}
