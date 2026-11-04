package com.mymesag.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 全局外观主题偏好（跟随系统 / 浅色 / 深色），持久化到 SharedPreferences。 */
object ThemeManager {
    private const val PREFS_NAME = "mymesag_theme"
    private const val KEY_MODE = "theme_mode"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val saved = p.getString(KEY_MODE, ThemeMode.SYSTEM.name)
        _themeMode.value = runCatching { ThemeMode.valueOf(saved!!) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
    }
}
