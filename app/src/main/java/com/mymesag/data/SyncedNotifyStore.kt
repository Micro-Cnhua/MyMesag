package com.mymesag.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 已同步通知指纹的持久化集合（断线补全同步用）。
 * 指纹 = stableKey + 内容哈希；只有真正 publish 过的通知才记入。
 * 重连扫描时以此判断哪些通知遗漏未同步，避免重复补发；进程重启后依然有效。
 * 有界 + 过期裁剪，防止无限增长。
 */
class SyncedNotifyStore(private val dataStore: DataStore<Preferences>) {

    @Serializable
    private data class Entry(val fp: String, val ts: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // fp -> 记入时间，按插入序（≈时间升序）维护，便于从最旧端裁剪
    private var cache: LinkedHashMap<String, Long>? = null

    suspend fun contains(fp: String): Boolean = mutex.withLock { ensureLoaded().containsKey(fp) }

    suspend fun snapshot(): Set<String> = mutex.withLock { ensureLoaded().keys.toSet() }

    suspend fun add(fps: Collection<String>) {
        if (fps.isEmpty()) return
        mutex.withLock {
            val map = ensureLoaded()
            val now = System.currentTimeMillis()
            for (fp in fps) {
                map.remove(fp)
                map[fp] = now
            }
            prune(map, now)
            persist(map)
        }
    }

    private suspend fun ensureLoaded(): LinkedHashMap<String, Long> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Long>()
        val raw = dataStore.data.first()[KEY]
        if (raw != null) {
            runCatching { json.decodeFromString(ListSerializer(Entry.serializer()), raw) }
                .getOrNull()?.forEach { map[it.fp] = it.ts }
        }
        prune(map, System.currentTimeMillis())
        cache = map
        return map
    }

    private fun prune(map: LinkedHashMap<String, Long>, now: Long) {
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now - e.value > EXPIRY_MS) iter.remove() else break
        }
        while (map.size > MAX_ENTRIES) {
            val oldest = map.keys.iterator().next()
            map.remove(oldest)
        }
    }

    private suspend fun persist(map: LinkedHashMap<String, Long>) {
        val list = map.map { Entry(it.key, it.value) }
        val raw = json.encodeToString(ListSerializer(Entry.serializer()), list)
        dataStore.edit { it[KEY] = raw }
    }

    companion object {
        private val KEY = stringPreferencesKey("synced_notify_fps")
        private const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 2000
    }
}