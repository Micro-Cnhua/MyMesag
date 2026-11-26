package com.mymesag.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.util.concurrent.CopyOnWriteArrayList

data class MqttConfig(
    val brokerUrl: String,
    val clientId: String,
    val username: String? = null,
    val password: String? = null,
    val keepAliveSeconds: Int = 60,
    val cleanSession: Boolean = true,
    val connectionTimeoutSeconds: Int = 15,
    val caCertPem: String? = null
)

enum class MqttState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class ReceivedMessage(
    val topic: String,
    val payload: String,
    val qos: Int = 0,
    val retained: Boolean = false
)

class MqttManager(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(MqttState.DISCONNECTED)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ReceivedMessage> = _messages.asSharedFlow()

    private val subscriptions = CopyOnWriteArrayList<Pair<String, Int>>()

    private var client: MqttAsyncClient? = null

    @Volatile
    private var manuallyDisconnected = false

    @Volatile
    private var reconnectAttempts = 0

    private var reconnectJob: kotlinx.coroutines.Job? = null

    private val maxReconnectAttempts = 10
    private val baseReconnectDelayMs = 2000L

    /** 快速退避用尽后的降频间隔：断线期间每隔此时长才真正试连一次（不再永久放弃）。 */
    private val plateauReconnectDelayMs = 30_000L

    /**
     * 本地网络是否可用（免费查询，不唤醒射频）。由 app 层注入 ConnectivityManager 判断；
     * 每次真正试连前先调用它把关，网络不通则跳过本次尝试。默认恒 true（未注入时不拦截）。
     */
    @Volatile
    var networkAvailable: () -> Boolean = { true }

    private var cachedSslSocketFactory: SSLSocketFactory? = null
    private var cachedCaCertPem: String? = null

    val isConnected: Boolean get() = client?.isConnected == true

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String) {
            Log.d(TAG, "connectComplete reconnect=$reconnect server=$serverURI")
            _state.value = MqttState.CONNECTED
            reconnectAttempts = 0
            reconnectJob?.cancel()
            resubscribeAll()
        }

        override fun connectionLost(cause: Throwable?) {
            Log.w(TAG, "connectionLost: ${cause?.message}", cause)
            if (manuallyDisconnected) return
            _state.value = MqttState.RECONNECTING
            scheduleReconnect()
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val text = String(message.payload, Charsets.UTF_8)
            if (BuildConfig.DEBUG) Log.d(TAG, "messageArrived topic=$topic payload=$text")
            else Log.d(TAG, "messageArrived topic=$topic bytes=${text.length}")
            scope.launch {
                _messages.emit(ReceivedMessage(topic, text, message.qos, message.isRetained))
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken) {
        }
    }

    private var lastConfig: MqttConfig? = null

    fun connect(cfg: MqttConfig) {
        if (_state.value == MqttState.CONNECTING ||
            _state.value == MqttState.CONNECTED
        ) return
        Log.d(TAG, "connect -> ${cfg.brokerUrl} clientId=${cfg.clientId}")
        manuallyDisconnected = false
        lastConfig = cfg
        reconnectAttempts = 0
        reconnectJob?.cancel()
        _state.value = MqttState.CONNECTING
        cleanupClient()
        doConnect(cfg)
    }

    private fun doConnect(cfg: MqttConfig) {
        scope.launch {
            try {
                val c = MqttAsyncClient(cfg.brokerUrl, cfg.clientId, MemoryPersistence())
                client = c
                c.setCallback(callback)
                val options = MqttConnectOptions().apply {
                    isCleanSession = cfg.cleanSession
                    keepAliveInterval = cfg.keepAliveSeconds
                    connectionTimeout = cfg.connectionTimeoutSeconds
                    isAutomaticReconnect = false
                    if (!cfg.username.isNullOrEmpty()) userName = cfg.username
                    if (!cfg.password.isNullOrEmpty()) password = cfg.password.toCharArray()
                    if (!cfg.caCertPem.isNullOrBlank()) {
                        socketFactory = getSslSocketFactory(cfg.caCertPem)
                    }
                }
                c.connect(options, null, connectListener)
            } catch (e: MqttException) {
                Log.e(TAG, "connect exception: ${e.message}", e)
                cleanupClient()
                scheduleReconnect()
            }
        }
    }

    fun subscribe(topic: String, qos: Int = 1) {
        if (topic.isBlank()) return
        Log.d(TAG, "subscribe topic=$topic qos=$qos")
        subscriptions.add(topic to qos)
        val c = client ?: return
        if (!c.isConnected) return
        try {
            c.subscribe(topic, qos, null, subscribeListener)
        } catch (e: MqttException) {
            // 操作失败不改连接状态：连接死活由 connectionLost 驱动，避免把瞬时失败误报成 ERROR
            Log.e(TAG, "subscribe exception: ${e.message}", e)
        }
    }

    /** 退订并从订阅表移除，避免重连时用旧主题重新订阅（groupToken 变化后重建订阅用）。 */
    fun unsubscribe(topic: String) {
        if (topic.isBlank()) return
        subscriptions.removeIf { it.first == topic }
        val c = client ?: return
        if (!c.isConnected) return
        try {
            c.unsubscribe(topic)
            Log.d(TAG, "unsubscribe topic=$topic")
        } catch (e: MqttException) {
            Log.e(TAG, "unsubscribe exception: ${e.message}", e)
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
        val c = client ?: return
        if (!c.isConnected) return
        if (BuildConfig.DEBUG) Log.d(TAG, "publish topic=$topic payload=$payload")
        else Log.d(TAG, "publish topic=$topic bytes=${payload.length}")
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            this.qos = qos
            isRetained = retained
        }
        try {
            c.publish(topic, message, null, publishListener)
        } catch (e: MqttException) {
            Log.e(TAG, "publish exception: ${e.message}", e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        manuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        cleanupClient()
        lastConfig = null
        _state.value = MqttState.DISCONNECTED
    }

    fun reconnectNow() {
        if (manuallyDisconnected) return
        reconnectAttempts = 0
        reconnectJob?.cancel()
        _state.value = MqttState.CONNECTING
        cleanupClient()
        lastConfig?.let { doConnect(it) }
    }

    private fun scheduleReconnect(forcePlateau: Boolean = false) {
        if (manuallyDisconnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // 断线门控：已连接则不再重连
            if (isConnected) return@launch
            // 降频：快速退避（最多 maxReconnectAttempts 次）用尽后，转为固定长间隔永续重试，不再永久放弃
            val fast = !forcePlateau && reconnectAttempts < maxReconnectAttempts
            val delayMs = if (fast) {
                baseReconnectDelayMs * (1 shl reconnectAttempts.coerceAtMost(6))
            } else {
                plateauReconnectDelayMs
            }
            Log.d(TAG, "reconnect attempt ${reconnectAttempts + 1} in ${delayMs}ms (fast=$fast)")
            _state.value = MqttState.RECONNECTING
            kotlinx.coroutines.delay(delayMs)
            if (manuallyDisconnected || isConnected) return@launch
            // 免费前置判断：本地网络不通就跳过本次试连（不唤醒射频、不计失败次数），降频后再查
            if (!networkAvailable()) {
                Log.d(TAG, "network unavailable, skip reconnect attempt")
                scheduleReconnect(forcePlateau = true)
                return@launch
            }
            reconnectAttempts++
            cleanupClient()
            lastConfig?.let { doConnect(it) }
        }
    }

    private fun cleanupClient() {
        try {
            val c = client
            if (c != null) {
                if (c.isConnected) {
                    c.disconnect(1000)
                }
                c.close()
            }
        } catch (e: MqttException) {
            Log.w(TAG, "cleanupClient: ${e.message}")
        }
        client = null
    }

    private fun resubscribeAll() {
        val c = client ?: return
        Log.d(TAG, "resubscribeAll count=${subscriptions.size}")
        scope.launch {
            kotlinx.coroutines.delay(500)
            for ((topic, qos) in subscriptions) {
                try {
                    c.subscribe(topic, qos, null, subscribeListener)
                    Log.d(TAG, "resubscribed to $topic")
                } catch (e: MqttException) {
                    Log.w(TAG, "resubscribe $topic failed: ${e.message}")
                }
            }
        }
    }

    private fun getSslSocketFactory(caCertPem: String): SSLSocketFactory {
        if (caCertPem == cachedCaCertPem && cachedSslSocketFactory != null) {
            return cachedSslSocketFactory!!
        }
        val factory = buildSslSocketFactory(caCertPem)
        cachedSslSocketFactory = factory
        cachedCaCertPem = caCertPem
        return factory
    }

    private fun buildSslSocketFactory(caCertPem: String): SSLSocketFactory {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(caCertPem.toByteArray(Charsets.UTF_8)))
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setCertificateEntry("ca", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val ctx = SSLContext.getInstance("TLSv1.2").apply { init(null, tmf.trustManagers, null) }
        return ctx.socketFactory
    }

    private val connectListener = object : IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) {
            Log.d(TAG, "connect onSuccess")
            reconnectAttempts = 0
        }

        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.w(TAG, "connect onFailure: ${exception?.message}", exception)
            cleanupClient()
            if (!manuallyDisconnected) {
                scheduleReconnect()
            }
        }
    }

    private val subscribeListener = object : IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) {
            Log.d(TAG, "subscribe onSuccess")
        }

        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.w(TAG, "subscribe onFailure: ${exception?.message}")
        }
    }

    private val publishListener = object : IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.w(TAG, "publish onFailure: ${exception?.message}")
        }
    }

    companion object {
        private const val TAG = "MqttManager"
    }
}
