package com.mymesag.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mymesag.model.NotificationEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * B 端"最近已发送通知"内容缓存（断线补全重放用）。
 * 保存完整 NotificationEvent（含原始 ts）；重放按原 ts 升序重发，保证 A 端水位与去重正确。
 * 有界（MAX_ENTRIES）+ 过期（EXPIRY_MS）裁剪，Mutex 串行化，内存缓存 + 落盘。
 */
class SentNotifyCache(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var cache: ArrayDeque<NotificationEvent>? = null

    suspend fun record(event: NotificationEvent) {
        mutex.withLock {
            val list = ensureLoaded()
            val fp = fingerprint(event)
            list.removeAll { fingerprint(it) == fp }
            list.addLast(event)
            prune(list)
            persist(list)
        }
    }

    /** 取 ts > sinceTs 的事件，按 ts 升序，最多 limit 条（超出取最近的）。 */
    suspend fun replaySince(sinceTs: Long, limit: Int): List<NotificationEvent> = mutex.withLock {
        val filtered = ensureLoaded().filter { it.ts > sinceTs }.sortedBy { it.ts }
        if (filtered.size > limit) filtered.takeLast(limit) else filtered
    }

    private suspend fun ensureLoaded(): ArrayDeque<NotificationEvent> {
        cache?.let { return it }
        val deque = ArrayDeque<NotificationEvent>()
        val raw = dataStore.data.first()[KEY]
        if (raw != null) {
            runCatching { json.decodeFromString(ListSerializer(NotificationEvent.serializer()), raw) }
                .getOrNull()?.forEach { deque.addLast(it) }
        }
        prune(deque)
        cache = deque
        return deque
    }

    private fun prune(list: ArrayDeque<NotificationEvent>) {
        val now = System.currentTimeMillis()
        while (list.isNotEmpty() && now - list.first().ts > EXPIRY_MS) list.removeFirst()
        while (list.size > MAX_ENTRIES) list.removeFirst()
    }

    private suspend fun persist(list: ArrayDeque<NotificationEvent>) {
        val raw = json.encodeToString(ListSerializer(NotificationEvent.serializer()), list.toList())
        dataStore.edit { it[KEY] = raw }
    }

    private fun fingerprint(e: NotificationEvent): String =
        e.stableKey + ":" + e.title + "\n" + e.text

    companion object {
        private val KEY = stringPreferencesKey("sent_notify_cache")
        private const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 500
    }
}