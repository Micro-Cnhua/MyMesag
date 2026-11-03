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
    val keepAliveSeconds: Int = 120,
    val cleanSession: Boolean = true,
    val connectionTimeoutSeconds: Int = 30,
    val willTopic: String? = null,
    val willPayload: String? = null,
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

    private var cachedSslSocketFactory: SSLSocketFactory? = null
    private var cachedCaCertPem: String? = null

    val isConnected: Boolean get() = client?.isConnected == true

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String) {
            Log.d(TAG, "connectComplete reconnect=$reconnect server=$serverURI")
            _state.value = MqttState.CONNECTED
            reconnectAttempts = 0
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
            Log.d(TAG, "messageArrived topic=$topic payload=$text")
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
                    if (!cfg.willTopic.isNullOrEmpty() && cfg.willPayload != null) {
                        setWill(cfg.willTopic, cfg.willPayload.toByteArray(Charsets.UTF_8), 1, true)
                    }
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
            Log.e(TAG, "subscribe exception: ${e.message}", e)
            _state.value = MqttState.ERROR
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
        val c = client ?: return
        if (!c.isConnected) return
        Log.d(TAG, "publish topic=$topic payload=$payload")
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            this.qos = qos
            isRetained = retained
        }
        try {
            c.publish(topic, message, null, publishListener)
        } catch (e: MqttException) {
            Log.e(TAG, "publish exception: ${e.message}", e)
            _state.value = MqttState.ERROR
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

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "max reconnect attempts reached, giving up")
            _state.value = MqttState.ERROR
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = baseReconnectDelayMs * (1 shl reconnectAttempts.coerceAtMost(6))
            Log.d(TAG, "scheduling reconnect attempt ${reconnectAttempts + 1} in ${delayMs}ms")
            _state.value = MqttState.RECONNECTING
            kotlinx.coroutines.delay(delayMs)
            if (manuallyDisconnected) return@launch
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
            _state.value = MqttState.ERROR
        }
    }

    private val publishListener = object : IMqttActionListener {
        override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
            Log.w(TAG, "publish onFailure: ${exception?.message}")
            _state.value = MqttState.ERROR
        }
    }

    companion object {
        private const val TAG = "MqttManager"
    }
}
