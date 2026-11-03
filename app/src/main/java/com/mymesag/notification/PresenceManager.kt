package com.mymesag.notification

import android.util.Log
import com.mymesag.data.DeviceIdentity
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttState
import com.mymesag.network.MqttTopics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 在线状态（阶段 9 / 项目书 3.3 presence 主题 + LWT）：
 * 订阅 presence/{userId}/+，连接建立后发布 online；断线由 LWT 遗嘱自动发布 offline。
 */
class PresenceManager(
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var published = false

    fun start() {
        scope.launch {
            val userId = deviceIdentity.userId()
            manager.subscribe(MqttTopics.presenceWildcard(userId))
        }
        scope.launch {
            manager.state.collect { state ->
                if (state == MqttState.CONNECTED) {
                    if (!published) {
                        published = true
                        val userId = deviceIdentity.userId()
                        val deviceId = deviceIdentity.deviceId()
                        manager.publish(MqttTopics.presence(userId, deviceId), "online", qos = 1, retained = true)
                        Log.d(TAG, "presence online published")
                    }
                } else {
                    published = false
                }
            }
        }
    }

    /** 构造 LWT 遗嘱主题（供连接配置使用）。 */
    suspend fun willTopic(): String {
        val userId = deviceIdentity.userId()
        val deviceId = deviceIdentity.deviceId()
        return MqttTopics.presence(userId, deviceId)
    }

    companion object {
        private const val TAG = "PresenceManager"
    }
}
