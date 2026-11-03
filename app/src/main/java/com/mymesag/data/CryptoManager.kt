package com.mymesag.data

import android.util.Log
import com.mymesag.crypto.CryptoBox
import com.mymesag.crypto.SeqWindow
import com.mymesag.model.EncryptedEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级加密管理器（阶段 7/8/9）：多设备两两会话密钥 + 序号 + 加密/解密 + 抗重放。
 * 密钥按 peer deviceId 存储；加密用「接收方」密钥，解密按「发送方」查密钥。
 */
object CryptoManager {

    private val box = CryptoBox()
    private val seqWindow = SeqWindow()
    private val seq = AtomicLong(System.currentTimeMillis() / 1000)

    private val keys = ConcurrentHashMap<String, ByteArray>()

    fun setKey(deviceId: String, key: ByteArray) {
        keys[deviceId] = key
        Log.d(TAG, "key set for $deviceId (${key.size} bytes)")
    }

    fun removeKey(deviceId: String) {
        keys.remove(deviceId)
        Log.d(TAG, "key removed for $deviceId")
    }

    fun clearKeys() {
        keys.clear()
    }

    /** 用接收方密钥加密；无该接收方密钥时返回 null。 */
    fun encrypt(plaintext: String, senderDeviceId: String, recipientDeviceId: String): EncryptedEnvelope? {
        val key = keys[recipientDeviceId] ?: return null
        return box.encrypt(plaintext, senderDeviceId, seq.incrementAndGet(), System.currentTimeMillis(), key, recipientDeviceId)
    }

    /** 解密并做抗重放校验；失败（篡改 / 重放 / 无密钥）返回 null。 */
    fun decrypt(envelope: EncryptedEnvelope): String? {
        if (!seqWindow.checkAndMark(envelope.senderDeviceId, envelope.seq)) {
            Log.w(TAG, "replay rejected sender=${envelope.senderDeviceId} seq=${envelope.seq}")
            return null
        }
        val key = keys[envelope.senderDeviceId] ?: run {
            Log.w(TAG, "no key for sender=${envelope.senderDeviceId}")
            return null
        }
        return try {
            box.decrypt(envelope, key)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed (tampered?): ${e.message}")
            null
        }
    }

    private const val TAG = "CryptoManager"
}
