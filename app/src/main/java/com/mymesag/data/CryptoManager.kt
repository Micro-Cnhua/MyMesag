package com.mymesag.data

import android.util.Log
import com.mymesag.crypto.CryptoBox
import com.mymesag.crypto.SeqWindow
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.SealedPayload
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级加密管理器（阶段 7/8/9）：多设备两两会话密钥 + 序号 + 加密/解密 + 抗重放。
 * 密钥按 peer deviceId 存储；加密用「接收方」密钥。外层信封不含设备标识，
 * 解密时逐个已配对密钥试解，成功后再按密文内的发送方做抗重放。
 */
object CryptoManager {

    private val box = CryptoBox()
    private val seqWindow = SeqWindow()
    private val seq = AtomicLong(System.currentTimeMillis() / 1000)
    private val json = Json { encodeDefaults = true }

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

    /** 用接收方密钥加密；发送方与序号封进密文，channel 写入 AAD。无该接收方密钥时返回 null。 */
    fun encrypt(
        plaintext: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        channel: String
    ): EncryptedEnvelope? {
        val key = keys[recipientDeviceId] ?: return null
        val sealed = json.encodeToString(
            SealedPayload.serializer(),
            SealedPayload(senderDeviceId, seq.incrementAndGet(), plaintext)
        )
        return box.encrypt(sealed, key, channel)
    }

    /**
     * 逐个已配对密钥试解密，成功后按密文内的发送方做抗重放；channel 须为消息实际到达的通道。
     * 失败（篡改 / 重放 / 跨通道 / 非本组消息）返回 null。
     */
    fun decrypt(envelope: EncryptedEnvelope, channel: String): String? {
        if (keys.isEmpty()) {
            Log.w(TAG, "no paired session key, cannot decrypt")
            return null
        }
        for (key in keys.values) {
            val sealedJson = try {
                box.decrypt(envelope, key, channel)
            } catch (e: Exception) {
                continue
            }
            val sealed = runCatching {
                json.decodeFromString(SealedPayload.serializer(), sealedJson)
            }.getOrNull() ?: continue
            if (!seqWindow.checkAndMark(sealed.sender, sealed.seq)) {
                Log.w(TAG, "replay rejected sender=${sealed.sender} seq=${sealed.seq}")
                return null
            }
            return sealed.body
        }
        Log.w(TAG, "decrypt failed: no matching peer key (tampered, wrong channel or not for this group)")
        return null
    }

    private const val TAG = "CryptoManager"
}
