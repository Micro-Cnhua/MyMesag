package com.mymesag.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 检测到有更新时的信息。 */
data class UpdateInfo(val tagName: String, val url: String, val title: String)

/**
 * 自动检查 GitHub 最新 Release：与本机 versionName 比较，若有更新则暴露 UpdateInfo。
 * 每个新版本只会「自动跳转」一次（避免每次启动都弹浏览器），其余靠首页顶部横幅手动点击。
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val currentVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update

    init {
        check()
    }

    fun check() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val body = candidateUrls().firstNotNullOfOrNull { fetchUrl(it) } ?: return@withContext
                    val obj = JSONObject(body)
                    val tag = obj.optString("tag_name")
                    if (tag.isBlank()) return@withContext
                    val url = obj.optString("html_url")
                    val title = obj.optString("name").ifBlank { tag }
                    val latest = tag.removePrefix("v").removePrefix("V")
                    _update.value = if (isNewer(latest, currentVersion)) UpdateInfo(tag, url, title) else null
                } catch (e: Exception) {
                    Log.w(TAG, "update check failed: ${e.message}")
                }
            }
        }
    }

    /** 用浏览器打开 Release 页。 */
    fun openRelease(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open release failed: ${it.message}") }
    }

    /** 加速镜像优先，直连 GitHub API 兜底。 */
    private fun candidateUrls(): List<String> = listOf(
        "https://ghfast.top/https://api.github.com/repos/$REPO_PATH/releases/latest",
        "https://api.github.com/repos/$REPO_PATH/releases/latest"
    )

    private fun fetchUrl(urlString: String): String? = runCatching {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "MyMesag-Android-App")
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    }.getOrNull()

    /** 语义化版本号比较：latest > current 时返回 true。 */
    private fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val a = latest.split(".").map { it.substringBefore("-").toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.substringBefore("-").toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    companion object {
        private const val TAG = "UpdateCheck"
        private const val REPO_PATH = "Micro-Cnhua/MyMesag"
    }
}
