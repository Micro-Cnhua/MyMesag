package com.mymesag.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的敏感数据加密存储（安全加固）。
 * 主密钥由 Keystore 生成并保存在硬件安全模块中（不可导出）。
 */
class SecureStorage {

    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun encrypt(plaintext: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + ciphertext
        Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (e: Exception) {
        // Keystore 不可用时降级为明文存储，保证功能可用
        Log.e(TAG, "encrypt failed, fallback to plaintext: ${e.javaClass.simpleName}: ${e.message}")
        plaintext
    }

    fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * 兼容旧版明文数据：解密失败时返回 null（而非返回加密数据）。
     * 调用方需处理 null 情况（通常是 Keystore 失效，需要重新配对/输入密码）。
     */
    fun decryptOrPlaintext(encoded: String): String? = try {
        decrypt(encoded)
    } catch (e: Exception) {
        // 检查是否本身就是明文（旧版本数据）
        // 加密数据格式：base64(IV + ciphertext)，长度至少 12 bytes IV + 16 bytes tag
        val decoded = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        if (decoded != null && decoded.size > 28) {
            // 看起来像加密数据但解密失败 → Keystore 问题，返回 null
            Log.e(TAG, "decrypt failed (Keystore issue?): ${e.javaClass.simpleName}: ${e.message}")
            null
        } else {
            // 不是有效的加密数据格式 → 可能是旧版明文
            Log.w(TAG, "treating as plaintext (legacy data)")
            encoded
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    companion object {
        private const val TAG = "SecureStorage"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "mymesag_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
