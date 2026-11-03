package com.mymesag.service

import android.util.LruCache

/**
 * 优化的消息去重管理器
 * 使用 LRU 缓存替代 ConcurrentHashMap，自动清理旧数据
 */
class DeduplicationManager(
    maxSize: Int = 1000 // 最多缓存 1000 条通知的哈希
) {
    private val hashCache = LruCache<String, Int>(maxSize)
    
    /**
     * 检查是否为重复消息
     * @return true 表示重复，false 表示新消息或内容已变化
     */
    fun isDuplicate(key: String, content: String): Boolean {
        val hash = fastHash(content)
        val previous = hashCache.get(key)
        
        return if (previous == hash) {
            true // 内容相同，是重复
        } else {
            hashCache.put(key, hash)
            false // 新消息或内容已变化
        }
    }
    
    /**
     * 移除指定 key 的缓存
     */
    fun remove(key: String) {
        hashCache.remove(key)
    }
    
    /**
     * 清空所有缓存
     */
    fun clear() {
        hashCache.evictAll()
    }
    
    /**
     * 快速哈希函数（比 SHA-256 快得多）
     * 使用 FNV-1a 算法，适合短字符串
     */
    private fun fastHash(input: String): Int {
        var hash: Int = 0x811c9dc5.toInt() // FNV offset basis
        for (char in input) {
            hash = hash xor char.code
            hash = hash * 0x01000193.toInt() // FNV prime
        }
        return hash
    }
    
    /**
     * 获取当前缓存大小
     */
    fun size(): Int = hashCache.size()
}
