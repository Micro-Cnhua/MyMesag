package com.mymesag.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mymesag.MainActivity
import com.mymesag.MyMesagApp
import com.mymesag.R
import com.mymesag.data.MqttConnection
import com.mymesag.network.MqttConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 前台服务（阶段 6）：常驻通知保活 + 自动连接 MQTT + 网络切换监听（网络恢复时主动重连）。
 * foregroundServiceType = dataSync（附录 B）。
 */
class MqttForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerNetworkCallback()
        autoConnect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterNetworkCallback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun autoConnect() {
        val app = application as MyMesagApp
        scope.launch {
            val broker = app.mqttConfigStore.savedBrokerUrl()
            if (broker.isNullOrBlank()) {
                Log.d(TAG, "no saved broker config, skip auto connect")
                return@launch
            }
            var clientId = app.mqttConfigStore.savedClientId()
            if (clientId.isNullOrBlank()) {
                // 首次连接或数据丢失，生成并保存 clientId
                clientId = "MyMesag-${UUID.randomUUID().toString().take(8)}"
                Log.i(TAG, "generated new clientId: $clientId")
                val username = app.mqttConfigStore.savedUsername()
                val password = app.mqttConfigStore.savedPassword()
                val caCertPem = app.mqttConfigStore.savedCaCertPem()
                app.mqttConfigStore.save(broker, clientId, username, password, caCertPem)
            }
            val username = app.mqttConfigStore.savedUsername()
            val password = app.mqttConfigStore.savedPassword()
            val caCertPem = app.mqttConfigStore.savedCaCertPem()
            val willTopic = app.presenceManager.willTopic()
            if (!MqttConnection.manager.isConnected) {
                MqttConnection.manager.connect(
                    MqttConfig(
                        brokerUrl = broker, clientId = clientId, username = username, password = password,
                        willTopic = willTopic, willPayload = "offline", caCertPem = caCertPem
                    )
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("MyMesag")
            .setContentText("通知同步服务运行中")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "保活服务", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, networkCallback)
            Log.d(TAG, "network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "register network callback failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregister network callback failed", e)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastNetworkChangeTime = 0L
        private val minIntervalMs = 5000L // 最小间隔 5 秒，避免频繁触发

        override fun onAvailable(network: Network) {
            val now = System.currentTimeMillis()
            if (now - lastNetworkChangeTime < minIntervalMs) {
                Log.d(TAG, "network available, but too frequent, skipping reconnect")
                return
            }
            lastNetworkChangeTime = now
            
            Log.d(TAG, "network available")
            if (!MqttConnection.manager.isConnected) {
                MqttConnection.manager.reconnectNow()
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "network lost")
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // 只在网络真正可用时触发
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                val now = System.currentTimeMillis()
                if (now - lastNetworkChangeTime < minIntervalMs) {
                    return
                }
                lastNetworkChangeTime = now
                
                if (!MqttConnection.manager.isConnected) {
                    MqttConnection.manager.reconnectNow()
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "keepalive_service"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "MqttFgService"
    }
}
