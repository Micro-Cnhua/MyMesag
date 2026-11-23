package com.mymesag.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 会话密钥派生与密钥对（项目书 11.1 / 11.2）：X25519 ECDH + HKDF-SHA256。 */
object SessionKeys {

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(): KeyPair {
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        return KeyPair(privateKey, publicKey)
    }

    /** 每两台设备之间派生一条独立会话密钥。 */
    fun deriveSessionKey(myPrivate: ByteArray, peerPublic: ByteArray): ByteArray {
        val sharedSecret = X25519.computeSharedSecret(myPrivate, peerPublic)
        return Hkdf.computeHkdf("HmacSHA256", sharedSecret, null, HKDF_INFO, CryptoBox.KEY_SIZE)
    }

    /** 公钥指纹：SHA-256 前 8 字节（二维码配对时比对）。 */
    fun fingerprint(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return hash.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
    }

    /** 双方都无既有组时，从会话密钥确定性派生组令牌：两端结果一致，无需比较大小或额外传输。 */
    fun deriveGroupToken(sessionKey: ByteArray): String {
        val raw = Hkdf.computeHkdf("HmacSHA256", sessionKey, null, GROUP_INFO, 16)
        return raw.joinToString("") { "%02x".format(it) }
    }

    /** 通道主题段：HMAC-SHA256(groupToken, channel) 前 8 字节，拿到公开信息也无法推导或跨通道关联。 */
    fun topicToken(groupToken: String, channel: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(groupToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(channel.toByteArray(Charsets.UTF_8))
        return raw.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
    }

    private val HKDF_INFO = "mymesag-session-v1".toByteArray(Charsets.UTF_8)
    private val GROUP_INFO = "mymesag-group-v1".toByteArray(Charsets.UTF_8)
}
