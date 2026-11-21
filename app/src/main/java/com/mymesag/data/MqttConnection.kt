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
    // 用 IO 而非主线程：MqttManager 内含阻塞调用（disconnect 等待、SSLContext 构建），跑主线程会卡顿甚至 ANR
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val manager = MqttManager(scope)
}
