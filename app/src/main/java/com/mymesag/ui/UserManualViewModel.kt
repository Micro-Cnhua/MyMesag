package com.mymesag.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UserManualViewModel(application: Application) : AndroidViewModel(application) {

    private val cacheDir = application.cacheDir
    private val cacheFile = File(cacheDir, "user-manual.html")
    private val cacheTimeFile = File(cacheDir, "user-manual-time.txt")
    private val cacheMaxAge = 6 * 60 * 60 * 1000L // 6 小时

    private val _content = MutableStateFlow<ManualState>(ManualState.Loading)
    val content: StateFlow<ManualState> = _content

    init {
        loadManual()
    }

    fun refresh() {
        _content.value = ManualState.Loading
        loadManual(forceRefresh = true)
    }

    private fun loadManual(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val cachedHtml = if (!forceRefresh) readCacheIfFresh() else null
            if (cachedHtml != null) {
                _content.value = ManualState.Ready(cachedHtml)
                return@launch
            }
            fetchManual()
        }
    }

    private suspend fun fetchManual() {
        withContext(Dispatchers.IO) {
            try {
                val markdown = candidateUrls().firstNotNullOfOrNull { fetchUrl(it) }
                if (markdown != null) {
                    val html = MarkdownRenderer.wrapHtml(MarkdownRenderer.markdownToHtml(markdown))
                    cacheFile.writeText(html, Charsets.UTF_8)
                    cacheTimeFile.writeText(System.currentTimeMillis().toString())
                    _content.value = ManualState.Ready(html)
                } else {
                    val cached = readCache()
                    if (cached != null) {
                        _content.value = ManualState.Ready(cached)
                    } else {
                        _content.value = ManualState.Error("无法加载使用手册，请检查网络连接")
                    }
                }
            } catch (e: Exception) {
                val cached = readCache()
                if (cached != null) {
                    _content.value = ManualState.Ready(cached)
                } else {
                    _content.value = ManualState.Error("加载失败: ${e.message}")
                }
            }
        }
    }

    /** 候选加速源，按顺序尝试：gh-proxy 镜像（实时回源）→ jsDelivr → 直连 GitHub */
    private fun candidateUrls(): List<String> = listOf(
        "https://ghfast.top/https://raw.githubusercontent.com/$REPO_PATH/$BRANCH/$FILE_PATH",
        "https://cdn.jsdelivr.net/gh/$REPO_PATH@$BRANCH/$FILE_PATH",
        "https://raw.githubusercontent.com/$REPO_PATH/$BRANCH/$FILE_PATH"
    )

    private fun fetchUrl(urlString: String): String? = runCatching {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }.getOrNull()

    private fun readCacheIfFresh(): String? {
        if (!cacheFile.exists() || !cacheTimeFile.exists()) return null
        val time = cacheTimeFile.readText().toLongOrNull() ?: return null
        if (System.currentTimeMillis() - time > cacheMaxAge) return null
        return cacheFile.readText()
    }

    private fun readCache(): String? = if (cacheFile.exists()) cacheFile.readText() else null

    sealed class ManualState {
        data object Loading : ManualState()
        data class Ready(val html: String) : ManualState()
        data class Error(val message: String) : ManualState()
    }

    companion object {
        private const val REPO_PATH = "Micro-Cnhua/MyMesag"   // ← 替换为你的 GitHub 仓库路径
        private const val BRANCH = "main"
        private const val FILE_PATH = "docs/user-manual.md"
    }
}
