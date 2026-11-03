package com.mymesag.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 设备标识（userId / deviceId）。
 * 首次访问时生成 UUID 并持久化；配对（阶段 8）会重新定义 userId 的来源。
 */
class DeviceIdentity(private val dataStore: DataStore<Preferences>) {

    @Volatile
    private var cachedUserId: String? = null

    @Volatile
    private var cachedDeviceId: String? = null

    suspend fun userId(): String = cachedUserId ?: loadOrCreate(KEY_USER_ID).also { cachedUserId = it }

    suspend fun deviceId(): String = cachedDeviceId ?: loadOrCreate(KEY_DEVICE_ID).also { cachedDeviceId = it }

    /** 配对时采用对方 userId，统一主题前缀（阶段 8）。 */
    suspend fun setUserId(userId: String) {
        cachedUserId = userId
        dataStore.edit { it[KEY_USER_ID] = userId }
    }

    /** 全量重置时清除 userId，下次访问重新生成。 */
    suspend fun resetUserId() {
        cachedUserId = null
        dataStore.edit { it.remove(KEY_USER_ID) }
    }

    private suspend fun loadOrCreate(key: Preferences.Key<String>): String {
        val existing = dataStore.data.first()[key]
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { it[key] = generated }
        return generated
    }

    companion object {
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }
}
