package com.mymesag.reply

import android.app.Notification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * A 端保存 Direct Reply 的 Notification.Action 引用（项目书 9.1）。
 * 以稳定通知 ID（stableKey）为键；超过 24 小时或通知被清除后失效。
 */
class ReplyActionCache {

    private data class Cached(
        val action: Notification.Action,
        val readAction: Notification.Action?,
        val ts: Long
    )

    private val map = ConcurrentHashMap<String, Cached>()

    fun put(stableKey: String, action: Notification.Action, readAction: Notification.Action? = null) {
        map[stableKey] = Cached(action, readAction, System.currentTimeMillis())
    }

    fun get(stableKey: String): Notification.Action? {
        val cached = map[stableKey] ?: return null
        if (expired(stableKey, cached)) return null
        return cached.action
    }

    /** 取同通知里「回复」旁边的「标记为已读」动作（可能不存在）。 */
    fun getReadAction(stableKey: String): Notification.Action? {
        val cached = map[stableKey] ?: return null
        if (expired(stableKey, cached)) return null
        return cached.readAction
    }

    private fun expired(stableKey: String, cached: Cached): Boolean {
        if (System.currentTimeMillis() - cached.ts > EXPIRY_MS) {
            map.remove(stableKey)
            Log.d(TAG, "expired action stableKey=$stableKey")
            return true
        }
        return false
    }

    fun remove(stableKey: String) {
        map.remove(stableKey)
    }

    companion object {
        private const val EXPIRY_MS = 24L * 60 * 60 * 1000
        private const val TAG = "ReplyActionCache"
    }
}
