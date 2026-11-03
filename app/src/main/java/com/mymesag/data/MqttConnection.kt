package com.mymesag.data

import com.mymesag.network.MqttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级共享的 MQTT 连接，供 UI（ViewModel）与 NotifyListenerService 共用。
 * 阶段 6 会将其迁移到 MqttForegroundService。
 */
object MqttConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val manager = MqttManager(scope)
}
