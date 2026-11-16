package com.mymesag.data

import android.util.Log
import com.mymesag.MyMesagApp
import com.mymesag.network.MqttConfig
import java.util.UUID

/**
 * 自动连接 MQTT 的共享逻辑：读取已保存配置，必要时生成 clientId，未连接则发起连接。
 * 供前台服务启动与"应用回前台"兜底共用，保证无论进程/服务是否重建，打开 App 即自愈连接。
 */
object MqttAutoConnect {
    private const val TAG = "MqttAutoConnect"

    suspend fun ensureConnected(app: MyMesagApp) {
        val manager = MqttConnection.manager
        if (manager.isConnected) return
        val store = app.mqttConfigStore
        val broker = store.savedBrokerUrl()
        if (broker.isNullOrBlank()) {
            Log.d(TAG, "no saved broker config, skip auto connect")
            return
        }
        var clientId = store.savedClientId()
        if (clientId.isNullOrBlank()) {
            clientId = "MyMesag-${UUID.randomUUID().toString().take(8)}"
            Log.i(TAG, "generated new clientId: $clientId")
            store.save(
                broker, clientId, store.savedUsername(), store.savedPassword(),
                store.savedCaCertPem(), store.savedDeviceTag()
            )
        }
        manager.connect(
            MqttConfig(
                brokerUrl = broker,
                clientId = clientId,
                username = store.savedUsername(),
                password = store.savedPassword(),
                keepAliveSeconds = store.savedKeepAlive(),
                willTopic = app.presenceManager.willTopic(),
                willPayload = "offline",
                caCertPem = store.savedCaCertPem()
            )
        )
    }
}