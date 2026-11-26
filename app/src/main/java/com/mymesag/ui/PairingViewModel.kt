package com.mymesag.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymesag.MyMesagApp
import com.mymesag.model.PairingPayload
import com.mymesag.pairing.PairingManager
import kotlinx.coroutines.launch

class PairingViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = (application as MyMesagApp).pairingManager

    var myQrContent by mutableStateOf("")
    var myFingerprint by mutableStateOf("")
    var peerFingerprint by mutableStateOf("")
    var status by mutableStateOf("")
    var paired by mutableStateOf(false)
    var pairedDevices by mutableStateOf<List<PairingManager.PairingInfo>>(emptyList())

    private var peerPayload: PairingPayload? = null

    init {
        viewModelScope.launch {
            val payload = runCatching { manager.buildPayload() }.getOrElse {
                status = "本机安全存储不可用，无法生成配对二维码：${it.javaClass.simpleName}"
                return@launch
            }
            myQrContent = manager.encodePayload(payload)
            myFingerprint = payload.fingerprint
            pairedDevices = manager.pairedDevices()
        }
    }

    fun onScanned(text: String) {
        val payload = manager.decodePayload(text)
        if (payload == null) {
            status = "扫码解析失败"
            return
        }
        peerPayload = payload
        peerFingerprint = payload.fingerprint
        status = "请比对两侧指纹一致后确认"
    }

    fun onImageResult(text: String?) {
        if (text == null) {
            status = "图片中未识别到二维码"
            return
        }
        onScanned(text)
    }

    fun confirmPairing() {
        val peer = peerPayload ?: return
        viewModelScope.launch {
            val result = runCatching { manager.completePairing(peer) }.getOrElse {
                status = "配对失败：本机安全存储不可用（${it.javaClass.simpleName}）"
                return@launch
            }
            if (result.success) {
                paired = true
                pairedDevices = manager.pairedDevices()
                status = "配对成功，会话密钥已建立"
            } else {
                status = "配对失败：${result.reason}"
            }
        }
    }

    fun removeDevice(deviceId: String) {
        viewModelScope.launch {
            val ok = runCatching { manager.removeDevice(deviceId) }.isSuccess
            pairedDevices = manager.pairedDevices()
            status = if (ok) "已移除设备并轮换密钥" else "移除失败：本机安全存储不可用，密钥未能轮换"
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            manager.resetAll()
            pairedDevices = emptyList()
            paired = false
            status = "已全量重置，请重新配对"
        }
    }
}
