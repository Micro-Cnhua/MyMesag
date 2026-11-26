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
import com.mymesag.data.MqttAutoConnect
import com.mymesag.data.MqttConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前台服务（阶段 6）：常驻通知保活 + 自动连接 MQTT + 网络切换监听（网络恢复时主动重连）。
 * foregroundServiceType = remoteMessaging：无累计时长上限，避免 dataSync 的 6 小时/24 小时配额
 * 耗尽后服务被系统强停、且无法再次启动。
 */
class MqttForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerNetworkCallback()
        // 注入免费的本地网络可用性判断：重连前先查 ConnectivityManager（不发起网络 I/O、不唤醒射频），
        // 网络不通则跳过试连。用 applicationContext 避免 lambda 持有 Service 实例。
        val appContext = applicationContext
        MqttConnection.manager.networkAvailable = {
            val cm = appContext.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
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
        scope.launch { MqttAutoConnect.ensureConnected(app) }
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
