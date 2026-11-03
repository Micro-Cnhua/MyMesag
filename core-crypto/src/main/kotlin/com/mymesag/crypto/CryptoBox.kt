package com.mymesag.crypto

import android.util.Base64
import com.google.crypto.tink.subtle.AesGcmJce
import com.mymesag.model.EncryptedEnvelope

/**
 * 端到端加密（项目书 6.4）：AEAD（AES-256-GCM）+ AAD 绑定元数据。
 * AAD 由 v/senderDeviceId/recipientDeviceId/seq/ts 组成，使外层元数据不可篡改；每次加密使用全新随机 IV。
 */
class CryptoBox {

    fun encrypt(
        plaintext: String,
        senderDeviceId: String,
        seq: Long,
        ts: Long,
        key: ByteArray,
        recipientDeviceId: String? = null
    ): EncryptedEnvelope {
        val aead = AesGcmJce(key)
        val aad = buildAad(1, senderDeviceId, seq, ts, recipientDeviceId)
        val full = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad)
        val iv = full.copyOfRange(0, IV_SIZE)
        val cipher = full.copyOfRange(IV_SIZE, full.size)
        return EncryptedEnvelope(
            v = 1,
            senderDeviceId = senderDeviceId,
            recipientDeviceId = recipientDeviceId,
            seq = seq,
            ts = ts,
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            cipher = Base64.encodeToString(cipher, Base64.NO_WRAP)
        )
    }

    /** 解密并校验 AAD；任何篡改都会抛 GeneralSecurityException。 */
    fun decrypt(envelope: EncryptedEnvelope, key: ByteArray): String {
        val aead = AesGcmJce(key)
        val aad = buildAad(envelope.v, envelope.senderDeviceId, envelope.seq, envelope.ts, envelope.recipientDeviceId)
        val iv = Base64.decode(envelope.iv, Base64.NO_WRAP)
        val cipher = Base64.decode(envelope.cipher, Base64.NO_WRAP)
        val full = iv + cipher
        val plaintext = aead.decrypt(full, aad)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun buildAad(v: Int, senderDeviceId: String, seq: Long, ts: Long, recipient: String?): ByteArray =
        "v=$v|sender=$senderDeviceId|recipient=$recipient|seq=$seq|ts=$ts".toByteArray(Charsets.UTF_8)

    companion object {
        const val IV_SIZE = 12
        const val KEY_SIZE = 32
    }
}
