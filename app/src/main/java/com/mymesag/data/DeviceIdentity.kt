package com.mymesag.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 设备标识（deviceId）与组路由令牌（groupToken）。
 * deviceId 首次访问时生成 UUID 并持久化；groupToken 由配对协商写入，未配对时为 null。
 */
class DeviceIdentity(private val dataStore: DataStore<Preferences>) {

    @Volatile
    private var cachedGroupToken: String? = null

    @Volatile
    private var cachedDeviceId: String? = null

    private val _groupTokenFlow = MutableStateFlow<String?>(null)

    /**
     * 当前 groupToken 的可观察流（null = 未配对）。配对协商 / 重置改变它时发出新值，
     * 供按路由段订阅的处理器实时重订，无需重启进程。
     */
    val groupTokenFlow: StateFlow<String?> = _groupTokenFlow.asStateFlow()

    suspend fun groupToken(): String? {
        val token = cachedGroupToken ?: dataStore.data.first()[KEY_GROUP_TOKEN].also { cachedGroupToken = it }
        if (_groupTokenFlow.value != token) _groupTokenFlow.value = token
        return token
    }

    suspend fun deviceId(): String = cachedDeviceId ?: loadOrCreate(KEY_DEVICE_ID).also { cachedDeviceId = it }

    /** 写入配对协商出的组令牌：全组共用，决定所有主题的路由段。 */
    suspend fun setGroupToken(token: String) {
        cachedGroupToken = token
        if (_groupTokenFlow.value != token) _groupTokenFlow.value = token
        dataStore.edit { it[KEY_GROUP_TOKEN] = token }
    }

    /** 全量重置时清除组令牌，回到未配对状态。 */
    suspend fun resetGroupToken() {
        cachedGroupToken = null
        _groupTokenFlow.value = null
        dataStore.edit { it.remove(KEY_GROUP_TOKEN) }
    }

    private suspend fun loadOrCreate(key: Preferences.Key<String>): String {
        val existing = dataStore.data.first()[key]
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { it[key] = generated }
        return generated
    }

    companion object {
        private val KEY_GROUP_TOKEN = stringPreferencesKey("group_token")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
    }
}