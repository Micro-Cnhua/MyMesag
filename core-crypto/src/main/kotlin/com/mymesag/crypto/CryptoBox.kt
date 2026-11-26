package com.mymesag.crypto

import android.util.Base64
import com.google.crypto.tink.subtle.AesGcmJce
import com.mymesag.model.EncryptedEnvelope

/**
 * 端到端加密（项目书 6.4）：AEAD（AES-256-GCM）+ AAD 绑定版本号与通道名。
 * 设备标识、序号、时间戳都在密文内，外层不留下任何可关联的明文元数据；
 * 密文只能在加密时指定的通道上解密（跨通道重放必然失败）；每次加密使用全新随机 IV。
 */
class CryptoBox {

    fun encrypt(plaintext: String, key: ByteArray, channel: String): EncryptedEnvelope {
        val aead = AesGcmJce(key)
        val full = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), buildAad(VERSION, channel))
        val iv = full.copyOfRange(0, IV_SIZE)
        val cipher = full.copyOfRange(IV_SIZE, full.size)
        return EncryptedEnvelope(
            v = VERSION,
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            cipher = Base64.encodeToString(cipher, Base64.NO_WRAP)
        )
    }

    /** 解密并校验 AAD（含通道名）；任何篡改或跨通道投递都会抛 GeneralSecurityException。 */
    fun decrypt(envelope: EncryptedEnvelope, key: ByteArray, channel: String): String {
        val aead = AesGcmJce(key)
        val iv = Base64.decode(envelope.iv, Base64.NO_WRAP)
        val cipher = Base64.decode(envelope.cipher, Base64.NO_WRAP)
        val plaintext = aead.decrypt(iv + cipher, buildAad(envelope.v, channel))
        return String(plaintext, Charsets.UTF_8)
    }

    private fun buildAad(v: Int, channel: String): ByteArray =
        "v=$v|channel=$channel".toByteArray(Charsets.UTF_8)

    companion object {
        const val IV_SIZE = 12
        const val KEY_SIZE = 32
        const val VERSION = 2
    }
}