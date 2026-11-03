package com.mymesag.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest

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

    private val HKDF_INFO = "mymesag-session-v1".toByteArray(Charsets.UTF_8)
}
