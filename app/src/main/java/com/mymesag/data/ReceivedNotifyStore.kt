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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A 端接收去重 + per-B 接收水位（断线补全用）。
 * 指纹集合：避免重放/重复通知重复堆叠（持久，进程重启有效），有界 + 过期裁剪。
 * 水位：每个发送端 B 已接收的最大事件 ts（B 时钟域），重连请求带它做增量重放。
 */
class ReceivedNotifyStore(private val dataStore: DataStore<Preferences>) {

    @Serializable
    private data class FpEntry(val fp: String, val ts: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var fps: LinkedHashMap<String, Long>? = null
    private var watermarks: HashMap<String, Long>? = null

    suspend fun contains(fp: String): Boolean = mutex.withLock { ensureFps().containsKey(fp) }

    suspend fun addFingerprint(fp: String) {
        mutex.withLock {
            val map = ensureFps()
            map.remove(fp)
            map[fp] = System.currentTimeMillis()
            pruneFps(map)
            persistFps(map)
        }
    }

    suspend fun watermark(deviceId: String): Long = mutex.withLock { ensureWatermarks()[deviceId] ?: 0L }

    suspend fun advanceWatermark(deviceId: String, ts: Long) {
        mutex.withLock {
            val map = ensureWatermarks()
            val cur = map[deviceId] ?: 0L
            if (ts > cur) {
                map[deviceId] = ts
                persistWatermarks(map)
            }
        }
    }

    private suspend fun ensureFps(): LinkedHashMap<String, Long> {
        fps?.let { return it }
        val map = LinkedHashMap<String, Long>()
        val raw = dataStore.data.first()[KEY_FPS]
        if (raw != null) {
            runCatching { json.decodeFromString(ListSerializer(FpEntry.serializer()), raw) }
                .getOrNull()?.forEach { map[it.fp] = it.ts }
        }
        pruneFps(map)
        fps = map
        return map
    }

    private suspend fun ensureWatermarks(): HashMap<String, Long> {
        watermarks?.let { return it }
        val map = HashMap<String, Long>()
        val raw = dataStore.data.first()[KEY_WM]
        if (raw != null) {
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
            }.getOrNull()?.forEach { (k, v) -> map[k] = v }
        }
        watermarks = map
        return map
    }

    private fun pruneFps(map: LinkedHashMap<String, Long>) {
        val now = System.currentTimeMillis()
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (now - e.value > EXPIRY_MS) iter.remove() else break
        }
        while (map.size > MAX_FPS) {
            map.remove(map.keys.iterator().next())
        }
    }

    private suspend fun persistFps(map: LinkedHashMap<String, Long>) {
        val list = map.map { FpEntry(it.key, it.value) }
        val raw = json.encodeToString(ListSerializer(FpEntry.serializer()), list)
        dataStore.edit { it[KEY_FPS] = raw }
    }

    private suspend fun persistWatermarks(map: HashMap<String, Long>) {
        val raw = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), map)
        dataStore.edit { it[KEY_WM] = raw }
    }

    companion object {
        private val KEY_FPS = stringPreferencesKey("received_notify_fps")
        private val KEY_WM = stringPreferencesKey("received_notify_watermarks")
        private const val EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_FPS = 2000
    }
}