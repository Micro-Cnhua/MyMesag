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
    private const val LEGACY_CLIENT_ID_LENGTH = 16

    /** 明文传输：主题名、密文长度与消息时序对链路可见。 */
    fun isPlaintext(brokerUrl: String): Boolean =
        brokerUrl.startsWith("tcp://") || brokerUrl.startsWith("ws://")

    /** 明文 + 账号密码：凭据会在链路上裸奔，任何连接入口都必须拒绝。 */
    fun credentialsExposed(brokerUrl: String, username: String?, password: String?): Boolean =
        isPlaintext(brokerUrl) && (!username.isNullOrBlank() || !password.isNullOrBlank())

    /** 公共 broker 上 8 位随机后缀易撞号被顶下线；15 位十六进制共 23 字符，仍在 MQTT 3.1 上限内。 */
    fun newClientId(): String =
        "MyMesag-" + UUID.randomUUID().toString().replace("-", "").take(15)

    suspend fun ensureConnected(app: MyMesagApp) {
        val manager = MqttConnection.manager
        if (manager.isConnected) {
            // 半开连接：本地 TCP 标志仍为 true，但心跳循环已被 Doze/ROM 冻住，必须强制重连
            if (manager.isLinkStale()) {
                Log.d(TAG, "connected but link stale, force reconnect")
                manager.reconnectNow()
            }
            return
        }
        val store = app.mqttConfigStore
        val broker = store.savedBrokerUrl()
        if (broker.isNullOrBlank()) {
            Log.d(TAG, "no saved broker config, skip auto connect")
            return
        }
        val username = store.savedUsername()
        val password = store.savedPassword()
        if (credentialsExposed(broker, username, password)) {
            Log.w(TAG, "refuse plaintext broker with credentials, switch to ssl://: $broker")
            return
        }
        var clientId = store.savedClientId()
        if (clientId.isNullOrBlank() || clientId.length <= LEGACY_CLIENT_ID_LENGTH) {
            clientId = newClientId()
            Log.i(TAG, "generated new clientId: $clientId")
            store.save(
                broker, clientId, username, password,
                store.savedCaCertPem(), store.savedDeviceTag()
            )
        }
        manager.connect(
            MqttConfig(
                brokerUrl = broker,
                clientId = clientId,
                username = username,
                password = password,
                keepAliveSeconds = store.savedKeepAlive(),
                caCertPem = store.savedCaCertPem()
            )
        )
    }
}