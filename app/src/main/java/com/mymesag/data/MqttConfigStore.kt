package com.mymesag.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 持久化 MQTT 连接配置（阶段 6：前台服务据此自动重连；含账号认证；密码加密存储）。 */
class MqttConfigStore(private val dataStore: DataStore<Preferences>, private val secure: SecureStorage) {

    val brokerUrl: Flow<String?> = dataStore.data.map { it[KEY_BROKER] }
    val clientId: Flow<String?> = dataStore.data.map { it[KEY_CLIENT] }
    val deviceTag: Flow<String?> = dataStore.data.map { it[KEY_DEVICE_TAG] }

    suspend fun savedBrokerUrl(): String? = dataStore.data.first()[KEY_BROKER]

    suspend fun savedClientId(): String? = dataStore.data.first()[KEY_CLIENT]

    suspend fun savedDeviceTag(): String? = dataStore.data.first()[KEY_DEVICE_TAG]

    suspend fun savedUsername(): String? = dataStore.data.first()[KEY_USERNAME]

    suspend fun savedPassword(): String? {
        val v = dataStore.data.first()[KEY_PASSWORD] ?: return null
        val decoded = secure.decryptOrPlaintext(v)
        if (decoded == null) {
            // 解密失败（Keystore 失效），需要重新输入密码
            android.util.Log.e("MqttConfigStore", "password decryption failed, clearing stored password")
            dataStore.edit { it.remove(KEY_PASSWORD) }
            return null
        }
        if (decoded == v) {
            // 旧明文，迁移为加密（失败不阻断读取）
            try {
                dataStore.edit { it[KEY_PASSWORD] = secure.encrypt(decoded) }
            } catch (e: Exception) {
                android.util.Log.w("MqttConfigStore", "migrate password failed: ${e.message}")
            }
        }
        return decoded
    }

    suspend fun savedCaCertPem(): String? = dataStore.data.first()[KEY_CA_CERT]

    suspend fun save(
        brokerUrl: String,
        clientId: String,
        username: String? = null,
        password: String? = null,
        caCertPem: String? = null,
        deviceTag: String? = null
    ) {
        dataStore.edit {
            it[KEY_BROKER] = brokerUrl
            it[KEY_CLIENT] = clientId
            if (username != null) it[KEY_USERNAME] = username else it.remove(KEY_USERNAME)
            if (password != null) it[KEY_PASSWORD] = secure.encrypt(password) else it.remove(KEY_PASSWORD)
            if (caCertPem != null) it[KEY_CA_CERT] = caCertPem else it.remove(KEY_CA_CERT)
            if (deviceTag != null) it[KEY_DEVICE_TAG] = deviceTag else it.remove(KEY_DEVICE_TAG)
        }
    }

    companion object {
        private val KEY_BROKER = stringPreferencesKey("mqtt_broker_url")
        private val KEY_CLIENT = stringPreferencesKey("mqtt_client_id")
        private val KEY_USERNAME = stringPreferencesKey("mqtt_username")
        private val KEY_PASSWORD = stringPreferencesKey("mqtt_password")
        private val KEY_CA_CERT = stringPreferencesKey("mqtt_ca_cert")
        private val KEY_DEVICE_TAG = stringPreferencesKey("mqtt_device_tag")
    }
}
