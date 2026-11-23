package com.mymesag.data

import android.util.Log
import com.mymesag.network.MqttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 让入站订阅跟随 groupToken 变化。
 *
 * 配对协商 / 重置会改变 groupToken，从而改变所有主题的路由段；订阅若停在旧令牌上就收不到入站消息。
 * 本函数 collect [DeviceIdentity.groupTokenFlow]：每次变化就退订旧主题、以新主题重订，
 * 启动时先播种一次。groupToken 为 null（未配对）时退订且不订阅。
 *
 * @param wildcardOf 由 groupToken 构造该处理器的通配订阅主题，如 { MqttTopics.replyWildcard(it) }。
 */
fun CoroutineScope.followGroupTopicSubscription(
    deviceIdentity: DeviceIdentity,
    manager: MqttManager,
    wildcardOf: (String) -> String,
    tag: String
) {
    launch {
        try {
            deviceIdentity.groupToken()
        } catch (e: Exception) {
            Log.e(tag, "seed groupToken error: ${e.message}", e)
        }
        var current: String? = null
        deviceIdentity.groupTokenFlow.collect { token ->
            val target = token?.let(wildcardOf)
            if (target == current) return@collect
            try {
                current?.let { manager.unsubscribe(it) }
                target?.let { manager.subscribe(it) }
                current = target
                Log.d(tag, "subscription updated -> $target")
            } catch (e: Exception) {
                Log.e(tag, "resubscribe error: ${e.message}", e)
            }
        }
    }
}