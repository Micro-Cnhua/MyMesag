package com.mymesag.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymesag.MyMesagApp
import com.mymesag.model.AppFilterConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppSelectViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as MyMesagApp).appFilterRepository

    private val installed: List<AppFilterConfig> = repo.installedApps()

    private val enabledState: StateFlow<List<AppFilterConfig>> = repo.appFilters
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val searchQuery = MutableStateFlow("")

    val query: StateFlow<String> = searchQuery

    fun setQuery(q: String) {
        searchQuery.value = q
    }

    /** 单一白名单：默认关闭，只有明确开启的应用才同步。 */
    val appList: StateFlow<List<AppFilterConfig>> =
        combine(enabledState, searchQuery) { enabled, query ->
            val enabledMap = enabled.associateBy { it.pkg }
            val q = query.trim()
            installed
                .map { info ->
                    info.copy(enabled = enabledMap[info.pkg]?.enabled ?: false)
                }
                .filter { info ->
                    q.isEmpty() ||
                        info.appName.contains(q, ignoreCase = true) ||
                        info.pkg.contains(q, ignoreCase = true)
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val syncVerification: StateFlow<Boolean> = repo.syncVerificationCodes
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val syncSendEnabled: StateFlow<Boolean> = repo.syncSendEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val syncReceiveEnabled: StateFlow<Boolean> = repo.syncReceiveEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSyncVerification(enabled: Boolean) {
        viewModelScope.launch { repo.setSyncVerificationCodes(enabled) }
    }

    fun setSyncSendEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setSyncSendEnabled(enabled) }
    }

    fun setSyncReceiveEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setSyncReceiveEnabled(enabled) }
    }

    fun toggleApp(pkg: String, appName: String, enabled: Boolean) {
        viewModelScope.launch { repo.setAppEnabled(pkg, appName, enabled) }
    }

    /** 启用所有短信应用（适配各厂商 ROM：.mms / .messaging / .conversations）。 */
    fun enableSmsApps() {
        viewModelScope.launch {
            installed
                .filter { it.pkg.endsWith(".mms") || it.pkg.endsWith(".messaging") || it.pkg.endsWith(".conversations") }
                .forEach { repo.setAppEnabled(it.pkg, it.appName, true) }
        }
    }
}
