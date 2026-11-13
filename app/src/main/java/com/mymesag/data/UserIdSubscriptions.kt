package com.mymesag.data

import android.util.Log
import com.mymesag.network.MqttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 让「按 userId 前缀」的入站订阅跟随 userId 变化。
 *
 * 处理器原先只在进程启动时用当时的 userId 订阅一次；配对统一 / 重置 / 升级重置改变 userId 后，
 * 订阅仍停在旧 uid 主题上，导致入站消息（reply/notify/call/presence）收不到，必须重启进程才恢复。
 * 本函数改为 collect [DeviceIdentity.userIdFlow]：每次 userId 变化就退订旧主题、以新主题重订，
 * 启动时先用当前 userId 播种一次。userId 为 null（已重置未再生成）时退订且不订阅。
 *
 * @param wildcardOf 由 userId 构造该处理器的通配订阅主题，如 { MqttTopics.replyWildcard(it) }。
 */
fun CoroutineScope.followUserIdSubscription(
    deviceIdentity: DeviceIdentity,
    manager: MqttManager,
    wildcardOf: (String) -> String,
    tag: String
) {
    launch {
        try {
            deviceIdentity.userId()
        } catch (e: Exception) {
            Log.e(tag, "seed userId error: ${e.message}", e)
        }
        var current: String? = null
        deviceIdentity.userIdFlow.collect { uid ->
            val target = uid?.let(wildcardOf)
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
