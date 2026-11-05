package com.mymesag

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mymesag.data.AppFilterRepository
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.MqttConfigStore
import com.mymesag.data.MqttConnection
import com.mymesag.data.SecureStorage
import com.mymesag.data.db.AppDatabase
import com.mymesag.notification.CallReceiver
import com.mymesag.notification.NotificationReceiver
import com.mymesag.notification.PresenceManager
import com.mymesag.pairing.PairingManager
import com.mymesag.reply.ReplyActionCache
import com.mymesag.reply.ReplyHandler
import com.mymesag.service.CallListener
import com.mymesag.service.MqttForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MyMesagApp : Application() {

    val dataStore: DataStore<Preferences> by preferencesDataStore(name = "mymesag_prefs")

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val deviceIdentity: DeviceIdentity by lazy { DeviceIdentity(dataStore) }

    val appFilterRepository: AppFilterRepository by lazy {
        AppFilterRepository(this, database.appFilterDao(), dataStore)
    }

    val replyActionCache: ReplyActionCache by lazy { ReplyActionCache() }

    val secureStorage: SecureStorage by lazy { SecureStorage() }

    val mqttConfigStore: MqttConfigStore by lazy { MqttConfigStore(dataStore, secureStorage) }

    val pairingManager: PairingManager by lazy { PairingManager(this, deviceIdentity, dataStore, secureStorage) }

    val presenceManager: PresenceManager by lazy { PresenceManager(MqttConnection.manager, deviceIdentity) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 会话密钥加载完成标志（避免消息到达时密钥未加载）
    private val sessionKeysLoaded = CompletableDeferred<Unit>()

    /** 等待会话密钥加载完成（供消息处理器调用）。 */
    suspend fun awaitSessionKeys() {
        sessionKeysLoaded.await()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MyMesagApp onCreate started")
        com.mymesag.ui.ThemeManager.init(this)
        // 升级到加密存储 + 加载会话密钥（阻塞直到完成，避免消息处理时密钥未加载）
        appScope.launch {
            try {
                Log.i(TAG, "starting migration and key loading...")
                migrateOnUpgrade()
                pairingManager.loadSessionKeys()
                val pairedCount = pairingManager.pairedDeviceIds().size
                Log.i(TAG, "session keys loaded, paired devices: $pairedCount")
            } catch (e: Exception) {
                Log.e(TAG, "failed to load session keys", e)
            } finally {
                sessionKeysLoaded.complete(Unit)
            }
        }
        // B 端通知还原：订阅 notify/{userId}/+ 并处理收到的通知事件
        NotificationReceiver(this, MqttConnection.manager, deviceIdentity).start()
        // A 端回复处理：订阅 reply/{userId}/+ 并执行回复、回传 ack
        ReplyHandler(this, MqttConnection.manager, deviceIdentity, replyActionCache).start()
        // B 端来电显示：订阅 call/{userId}/+
        CallReceiver(this, MqttConnection.manager, deviceIdentity).start()
        // A 端来电监听：监听通话状态并发布 CallEvent
        CallListener(this, MqttConnection.manager, deviceIdentity).start()
        // 在线状态（presence + LWT）
        presenceManager.start()
        // 前台服务保活 + 自动连接 + 网络切换监听
        startKeepAliveService()
    }

    /** 旧版本（明文存储）升级到加密存储时，清空配对 + 密码，避免旧明文迁移出错。 */
    private suspend fun migrateOnUpgrade() {
        val prefs = dataStore.data.first()
        if (prefs[KEY_STORAGE_VERSION] != null) return
        dataStore.edit {
            it.remove(KEY_PAIR_PRIVATE)
            it.remove(KEY_PAIR_PUBLIC)
            it.remove(KEY_PAIR_PEERS)
            it.remove(KEY_MQTT_PASSWORD)
            it[KEY_STORAGE_VERSION] = STORAGE_VERSION
        }
        deviceIdentity.resetUserId()
        CryptoManager.clearKeys()
        Log.i(TAG, "storage upgraded to encrypted: pairing + password reset, please re-pair")
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, MqttForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "MyMesagApp"
        private const val STORAGE_VERSION = "2"
        private val KEY_STORAGE_VERSION = stringPreferencesKey("storage_version")
        private val KEY_PAIR_PRIVATE = stringPreferencesKey("pair_private")
        private val KEY_PAIR_PUBLIC = stringPreferencesKey("pair_public")
        private val KEY_PAIR_PEERS = stringPreferencesKey("pair_peers")
        private val KEY_MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
    }
}
