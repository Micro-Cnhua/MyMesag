package com.mymesag.pairing

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mymesag.crypto.SessionKeys
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.SecureStorage
import com.mymesag.model.PairingPayload
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 配对与密钥管理（阶段 8/9 / 项目书第 11 章）：
 * 每两台设备一条会话密钥（X25519 ECDH + HKDF），密钥按 peer deviceId 存储。
 * 私钥与会话密钥用 Android Keystore 加密存储。
 */
class PairingManager(
    private val context: Context,
    private val deviceIdentity: DeviceIdentity,
    private val dataStore: DataStore<Preferences>,
    private val secure: SecureStorage
) {
    private val json = Json { encodeDefaults = true }

    suspend fun myKeyPair(): SessionKeys.KeyPair {
        val prefs = dataStore.data.first()
        val priv = prefs[KEY_PRIVATE]
        val pub = prefs[KEY_PUBLIC]
        if (priv != null && pub != null) {
            val privPlain = secure.decryptOrPlaintext(priv)
            if (privPlain == null) {
                // 解密失败（Keystore 失效），重新生成密钥对
                Log.e(TAG, "private key decryption failed, regenerating keypair")
                dataStore.edit {
                    it.remove(KEY_PRIVATE)
                    it.remove(KEY_PUBLIC)
                    it.remove(KEY_PEERS)
                }
                CryptoManager.clearKeys()
            } else {
                if (privPlain == priv) {
                    // 旧明文，迁移为加密（失败不阻断读取）
                    try {
                        dataStore.edit { it[KEY_PRIVATE] = secure.encrypt(privPlain) }
                    } catch (e: Exception) {
                        Log.w(TAG, "migrate private key failed: ${e.message}")
                    }
                }
                return SessionKeys.KeyPair(
                    Base64.decode(privPlain, Base64.NO_WRAP),
                    Base64.decode(pub, Base64.NO_WRAP)
                )
            }
        }
        val kp = SessionKeys.generateKeyPair()
        dataStore.edit {
            it[KEY_PRIVATE] = secure.encrypt(Base64.encodeToString(kp.privateKey, Base64.NO_WRAP))
            it[KEY_PUBLIC] = Base64.encodeToString(kp.publicKey, Base64.NO_WRAP)
        }
        return kp
    }

    fun deviceName(): String = Build.MODEL ?: "Android"

    suspend fun buildPayload(): PairingPayload {
        val kp = myKeyPair()
        val userId = deviceIdentity.userId()
        val deviceId = deviceIdentity.deviceId()
        val broker = dataStore.data.first()[KEY_BROKER] ?: ""
        return PairingPayload(
            userId = userId,
            deviceId = deviceId,
            deviceName = deviceName(),
            pubKey = Base64.encodeToString(kp.publicKey, Base64.NO_WRAP),
            fingerprint = SessionKeys.fingerprint(kp.publicKey),
            broker = broker,
            topicPrefix = userId
        )
    }

    suspend fun completePairing(peer: PairingPayload): PairingResult {
        val peerPubKey = Base64.decode(peer.pubKey, Base64.NO_WRAP)
        val computedFingerprint = SessionKeys.fingerprint(peerPubKey)
        if (computedFingerprint != peer.fingerprint) {
            return PairingResult(success = false, fingerprint = computedFingerprint, reason = "指纹不一致")
        }
        val myKp = myKeyPair()
        val sessionKey = SessionKeys.deriveSessionKey(myKp.privateKey, peerPubKey)
        // 更新本 peer 的会话密钥（多设备：每个 peer 一条独立密钥）
        val peers = loadPeers().toMutableList()
        peers.removeAll { it.deviceId == peer.deviceId }
        peers.add(PeerRecord(peer.deviceId, peer.deviceName, Base64.encodeToString(sessionKey, Base64.NO_WRAP)))
        dataStore.edit {
            it[KEY_PEERS] = secure.encrypt(json.encodeToString(ListSerializer(PeerRecord.serializer()), peers))
            it[KEY_BROKER] = peer.broker
        }
        // 确定性统一 userId：取较小值，避免双方同时扫旧二维码导致互换
        val myUserId = deviceIdentity.userId()
        val unifiedUserId = if (myUserId < peer.userId) myUserId else peer.userId
        deviceIdentity.setUserId(unifiedUserId)
        CryptoManager.setKey(peer.deviceId, sessionKey)
        return PairingResult(success = true, fingerprint = computedFingerprint, reason = "OK")
    }

    /** 应用启动时加载所有已保存的会话密钥。 */
    suspend fun loadSessionKeys() {
        for (peer in loadPeers()) {
            CryptoManager.setKey(peer.deviceId, Base64.decode(peer.keyBase64, Base64.NO_WRAP))
        }
    }

    fun encodePayload(payload: PairingPayload): String =
        json.encodeToString(PairingPayload.serializer(), payload)

    fun decodePayload(text: String): PairingPayload? = runCatching {
        json.decodeFromString(PairingPayload.serializer(), text)
    }.getOrNull()

    data class PairingInfo(val deviceId: String, val deviceName: String)

    suspend fun pairedDevices(): List<PairingInfo> = loadPeers().map { PairingInfo(it.deviceId, it.deviceName) }

    suspend fun pairedDeviceIds(): List<String> = loadPeers().map { it.deviceId }

    suspend fun removeDevice(deviceId: String) {
        val peers = loadPeers().toMutableList()
        peers.removeAll { it.deviceId == deviceId }
        dataStore.edit { it[KEY_PEERS] = secure.encrypt(json.encodeToString(ListSerializer(PeerRecord.serializer()), peers)) }
        CryptoManager.removeKey(deviceId)
    }

    suspend fun resetAll() {
        dataStore.edit {
            it.remove(KEY_PRIVATE)
            it.remove(KEY_PUBLIC)
            it.remove(KEY_PEERS)
            it.remove(KEY_BROKER)
        }
        deviceIdentity.resetUserId()
        CryptoManager.clearKeys()
    }

    private suspend fun loadPeers(): List<PeerRecord> {
        val s = dataStore.data.first()[KEY_PEERS] ?: return emptyList()
        val decrypted = secure.decryptOrPlaintext(s)
        if (decrypted == null) {
            // 解密失败（Keystore 失效），清空配对数据
            Log.e(TAG, "peers decryption failed, clearing peers")
            dataStore.edit { it.remove(KEY_PEERS) }
            return emptyList()
        }
        if (decrypted == s) {
            // 旧明文，迁移为加密（失败不阻断读取）
            try {
                dataStore.edit { it[KEY_PEERS] = secure.encrypt(decrypted) }
            } catch (e: Exception) {
                Log.w(TAG, "migrate peers failed: ${e.message}")
            }
        }
        return runCatching { json.decodeFromString(ListSerializer(PeerRecord.serializer()), decrypted) }.getOrDefault(emptyList())
    }

    @Serializable
    data class PeerRecord(val deviceId: String, val deviceName: String, val keyBase64: String)

    data class PairingResult(val success: Boolean, val fingerprint: String, val reason: String)

    companion object {
        private const val TAG = "PairingManager"
        private val KEY_PRIVATE = stringPreferencesKey("pair_private")
        private val KEY_PUBLIC = stringPreferencesKey("pair_public")
        private val KEY_PEERS = stringPreferencesKey("pair_peers")
        private val KEY_BROKER = stringPreferencesKey("mqtt_broker_url")
    }
}
