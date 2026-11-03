package com.mymesag.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 消息重试管理器
 * 用于在网络不稳定时重试发送失败的消息
 */
class MessageRetryManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val retryQueue = ConcurrentLinkedQueue<RetryMessage>()
    private val maxRetries = 3
    private val baseDelayMs = 2000L
    
    data class RetryMessage(
        val id: String,
        val topic: String,
        val payload: String,
        val qos: Int,
        var retryCount: Int = 0,
        val sendAction: suspend (String, String, Int) -> Boolean
    )
    
    /**
     * 添加消息到重试队列
     */
    fun enqueue(message: RetryMessage) {
        retryQueue.offer(message)
        Log.d(TAG, "Message queued for retry: ${message.id}")
        processQueue()
    }
    
    /**
     * 处理重试队列
     */
    private fun processQueue() {
        scope.launch {
            while (retryQueue.isNotEmpty()) {
                val message = retryQueue.poll() ?: break
                
                if (message.retryCount >= maxRetries) {
                    Log.w(TAG, "Message ${message.id} exceeded max retries, discarding")
                    continue
                }
                
                val delayMs = baseDelayMs * (1 shl message.retryCount)
                Log.d(TAG, "Retrying message ${message.id} in ${delayMs}ms (attempt ${message.retryCount + 1}/$maxRetries)")
                delay(delayMs)
                
                val success = try {
                    message.sendAction(message.topic, message.payload, message.qos)
                } catch (e: Exception) {
                    Log.e(TAG, "Retry failed for message ${message.id}", e)
                    false
                }
                
                if (!success) {
                    message.retryCount++
                    retryQueue.offer(message)
                    Log.d(TAG, "Message ${message.id} re-queued, retry count: ${message.retryCount}")
                } else {
                    Log.d(TAG, "Message ${message.id} sent successfully after retry")
                }
            }
        }
    }
    
    /**
     * 清空重试队列
     */
    fun clear() {
        retryQueue.clear()
        Log.d(TAG, "Retry queue cleared")
    }
    
    /**
     * 获取队列大小
     */
    fun size(): Int = retryQueue.size
    
    companion object {
        private const val TAG = "MsgRetryManager"
    }
}
