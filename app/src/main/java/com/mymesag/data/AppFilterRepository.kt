package com.mymesag.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.inputmethod.InputMethod
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mymesag.data.db.AppFilterDao
import com.mymesag.data.db.AppFilterEntity
import com.mymesag.model.AppFilterConfig
import com.mymesag.model.FilterMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用过滤仓库（项目书 2.2 / 8.2）：
 * - FilterMode 存 DataStore；
 * - 每个 App 的启用状态存 Room；
 * - 敏感规则为内置默认。
 */
class AppFilterRepository(
    private val context: Context,
    private val dao: AppFilterDao,
    private val dataStore: DataStore<Preferences>
) {

    // IME 判定结果缓存：queryIntentServices 是 binder IPC，每条通知都查开销大；
    // 输入法身份在进程存活期内基本不变，缓存后避免重复 IPC。
    private val imeCache = ConcurrentHashMap<String, Boolean>()

    val filterMode: Flow<FilterMode> = dataStore.data.map { prefs ->
        prefs[KEY_MODE]?.let { runCatching { FilterMode.valueOf(it) }.getOrNull() }
            ?: FilterMode.BLACKLIST
    }

    val appFilters: Flow<List<AppFilterConfig>> = dao.observeAll().map { list ->
        list.map { AppFilterConfig(it.pkg, it.appName, it.enabled) }
    }

    val syncVerificationCodes: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_VERIFICATION] ?: false
    }

    /** 发送开关（本设备是否采集并发送通知，默认开）。 */
    val syncSendEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_SEND] ?: true
    }

    /** 接收开关（本设备是否接收并显示远端通知，默认开）。 */
    val syncReceiveEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_RECEIVE] ?: true
    }

    suspend fun setFilterMode(mode: FilterMode) {
        dataStore.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setSyncVerificationCodes(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_VERIFICATION] = enabled }
    }

    suspend fun setSyncSendEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_SEND] = enabled }
    }

    suspend fun setSyncReceiveEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SYNC_RECEIVE] = enabled }
    }

    suspend fun setAppEnabled(pkg: String, appName: String, enabled: Boolean) {
        dao.upsert(AppFilterEntity(pkg, appName, enabled))
    }

    /** 已安装应用列表（图标/名称/包名，用于应用选择 UI）。 */
    fun installedApps(): List<AppFilterConfig> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .mapNotNull { info ->
                    val pkg = info.packageName ?: return@mapNotNull null
                    AppFilterConfig(pkg, pm.getApplicationLabel(info).toString(), enabled = false)
                }
                .sortedBy { it.appName }
        }.getOrDefault(emptyList())
    }

    /**
     * 过滤判断（项目书 2.2）。
     * 敏感应用排除与内置默认排除优先于任何模式。
     */
    suspend fun shouldSync(pkg: String, title: String?, text: String?): Boolean {
        if (!syncSendEnabled.first()) {
            Log.d(TAG, "send disabled, skip pkg=$pkg")
            return false
        }
        val allowVerification = syncVerificationCodes.first()
        if (SensitiveRules.isSensitive(pkg, title, text, allowVerification)) {
            Log.d(TAG, "filter sensitive pkg=$pkg")
            return false
        }
        if (isBuiltinExcluded(pkg)) {
            Log.d(TAG, "filter builtin pkg=$pkg")
            return false
        }
        // 白名单：只有明确开启的应用才同步
        val enabled = dao.isEnabled(pkg)
        Log.d(TAG, "shouldSync pkg=$pkg enabled=$enabled -> ${enabled == true}")
        return enabled == true
    }

    /** 内置默认排除：系统 UI、本应用自身、输入法。 */
    private fun isBuiltinExcluded(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "com.mymesag") return true
        return isIme(pkg)
    }

    private fun isIme(pkg: String): Boolean = imeCache.getOrPut(pkg) {
        val intent = Intent(InputMethod.SERVICE_INTERFACE).setPackage(pkg)
        runCatching {
            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "AppFilter"
        private val KEY_MODE = stringPreferencesKey("filter_mode")
        private val KEY_SYNC_VERIFICATION = booleanPreferencesKey("sync_verification_codes")
        private val KEY_SYNC_SEND = booleanPreferencesKey("sync_send_enabled")
        private val KEY_SYNC_RECEIVE = booleanPreferencesKey("sync_receive_enabled")
    }
}
