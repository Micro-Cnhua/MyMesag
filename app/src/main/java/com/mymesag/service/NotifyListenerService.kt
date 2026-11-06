package com.mymesag.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import com.mymesag.MyMesagApp
import com.mymesag.data.CryptoManager
import com.mymesag.data.MqttConnection
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.NotificationEvent
import com.mymesag.network.MqttTopics
import com.mymesag.notification.CallReceiver
import com.mymesag.notification.NotificationExtractor
import com.mymesag.notification.NotificationParser
import com.mymesag.notification.NotificationReceiver
import com.mymesag.reply.ReplyConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 通知采集（阶段 2）：监听系统通知 → 过滤 → 去重（不变量 1）→ 封装 NotificationEvent → publish。
 */
class NotifyListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { encodeDefaults = true }

    // 优化的去重管理器（使用 LRU 缓存）
    private val deduplicationManager = DeduplicationManager()

    // 稳定通知 ID -> 首次采集的标题/正文/联系人（移除事件用它匹配分组，通话类通知内容会变化）
    // 使用 LRU 缓存，最多保留 500 条
    private val maxCapturedSize = 500
    private val capturedTitle = LruCache<String, String>(maxCapturedSize)
    private val capturedText = LruCache<String, String>(maxCapturedSize)
    private val capturedContact = LruCache<String, String>(maxCapturedSize)

    // 每设备单调递增序号（阶段 7 将持久化用于抗重放）
    private val seq = AtomicLong(0)

    private lateinit var app: MyMesagApp

    // 提前过滤缓存：发送开关 + 已开启同步的应用白名单（内存常驻，随设置变更实时刷新）。
    // 命中失败的通知在协程/数据库/IME IPC 之前就被同步丢弃，省去绝大多数无谓开销。
    @Volatile private var sendEnabledCache = true
    @Volatile private var enabledPkgsCache: Set<String> = emptySet()
    @Volatile private var filterCacheReady = false

    // 发布去抖：按 stableKey 合并「解析后消息行相同」的重复更新（如微信 [N条] 计数变化）。
    // 出现真正的新消息行时立即发出上一条，保证 B 端累积的多条历史不丢失。
    private class Pending(val event: NotificationEvent, val line: String)
    private val publishLock = Any()
    private val pending = HashMap<String, Pending>()
    private val pendingJobs = HashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        app = application as MyMesagApp
        Log.i(TAG, "NotifyListenerService created - notification listener is ACTIVE")
        scope.launch {
            app.appFilterRepository.syncSendEnabled.collect { sendEnabledCache = it }
        }
        scope.launch {
            app.appFilterRepository.appFilters.collect { list ->
                enabledPkgsCache = list.filter { it.enabled }.map { it.pkg }.toHashSet()
                filterCacheReady = true
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected - service is now bound to system")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "onListenerDisconnected - service disconnected from system")
        requestRebind(ComponentName(this, NotifyListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        Log.d(TAG, "onNotificationPosted: pkg=${sbn.packageName} tag=${sbn.tag}")
        // 防回环（不变量 2）：本应用重建的远端通知/来电通知直接丢弃
        if (sbn.tag == NotificationReceiver.SYNCED_TAG || sbn.tag == CallReceiver.CALL_TAG) {
            Log.d(TAG, "skip synced notification (防回环)")
            return
        }
        val pkg = sbn.packageName
        // 提前过滤：发送关闭或应用未开启同步时，直接同步丢弃（不提取内容、不起协程、不查库）。
        // 缓存就绪后才启用快速路径，避免启动瞬间白名单未加载而误丢通知。
        if (filterCacheReady && (!sendEnabledCache || pkg !in enabledPkgsCache)) {
            return
        }
        val title = NotificationExtractor.title(sbn)
        val text = NotificationExtractor.text(sbn)

        scope.launch {
            try {
                if (!app.appFilterRepository.shouldSync(pkg, title, text)) {
                    Log.d(TAG, "filtered out pkg=$pkg")
                    return@launch
                }

                val stableKey = sbn.key
                // 使用优化的去重管理器
                if (deduplicationManager.isDuplicate(stableKey, "$title\n$text")) {
                    Log.d(TAG, "dedup unchanged stableKey=$stableKey")
                    return@launch
                }
                
                // 检查是否为新消息（之前没有记录）
                val update = capturedTitle.get(stableKey) != null
                val contact = computeContact(title, text)
                
                // 首次采集时记住标题/正文/联系人（移除事件用它匹配分组）
                if (!update) {
                    capturedTitle.put(stableKey, title)
                    capturedText.put(stableKey, text)
                    capturedContact.put(stableKey, contact)
                }

                if (!MqttConnection.manager.isConnected) {
                    Log.d(TAG, "not connected, skip publish pkg=$pkg")
                    return@launch
                }

                val userId = app.deviceIdentity.userId()
                val deviceId = app.deviceIdentity.deviceId()
                val deviceTag = app.mqttConfigStore.savedDeviceTag() ?: ""

                // Direct Reply 能力判断（项目书 9.1）：仅支持矩阵中的应用 + 存在 RemoteInput 才记录
                val replyAction = sbn.notification.actions
                    ?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
                val canReply = replyAction != null && ReplyConstants.isSupported(pkg)
                if (replyAction != null && canReply) {
                    app.replyActionCache.put(stableKey, replyAction)
                }

                val event = NotificationEvent(
                    id = UUID.randomUUID().toString(),
                    from = deviceId,
                    ts = System.currentTimeMillis(),
                    seq = seq.incrementAndGet(),
                    stableKey = stableKey,
                    update = update,
                    pkg = pkg,
                    appName = appLabel(pkg),
                    title = title,
                    text = text,
                    contact = contact,
                    priority = sbn.notification.priority,
                    canReply = canReply,
                    deviceTag = deviceTag
                )

                schedulePublish(event)
                Log.d(TAG, "published pkg=$pkg update=$update")
            } catch (e: Exception) {
                Log.e(TAG, "onNotificationPosted error", e)
            }
        }
    }

    private suspend fun publishEvent(event: NotificationEvent) {
        val userId = app.deviceIdentity.userId()
        val deviceId = app.deviceIdentity.deviceId()
        val topic = MqttTopics.notify(userId, deviceId)
        val plaintext = json.encodeToString(NotificationEvent.serializer(), event)
        // 多设备两两密钥：按每个接收方分别加密，接收方凭 recipientDeviceId 过滤
        for (peerId in app.pairingManager.pairedDeviceIds()) {
            val envelope = CryptoManager.encrypt(plaintext, deviceId, peerId) ?: continue
            val payload = json.encodeToString(EncryptedEnvelope.serializer(), envelope)
            MqttConnection.manager.publish(topic, payload)
        }
    }

    /**
     * 发布去抖（按 stableKey）：
     * - 解析出与待发事件相同的消息行时，视为重复更新（如 [N条] 计数变化），合并为最新一条，延迟发布；
     * - 出现不同的新消息行时，立即发出上一条再排程新一条，确保 B 端累积的多条历史不丢失；
     * - 移除事件会调用 [cancelPending] 取消待发，避免清空分组后补发旧消息。
     */
    private fun schedulePublish(event: NotificationEvent) {
        val parsed = NotificationParser.parse(event)
        val line = parsed.message.ifBlank { event.text }
        val key = event.stableKey
        var flushNow: NotificationEvent? = null
        synchronized(publishLock) {
            val prev = pending[key]
            pendingJobs.remove(key)?.cancel()
            if (prev != null && prev.line != line) {
                flushNow = prev.event
                pending.remove(key)
            }
            pending[key] = Pending(event, line)
            pendingJobs[key] = scope.launch {
                delay(PUBLISH_DEBOUNCE_MS)
                val ev = synchronized(publishLock) {
                    pendingJobs.remove(key)
                    pending.remove(key)?.event
                }
                if (ev != null && MqttConnection.manager.isConnected) publishEvent(ev)
            }
        }
        val ev = flushNow
        if (ev != null && MqttConnection.manager.isConnected) {
            scope.launch { publishEvent(ev) }
        }
    }

    private fun cancelPending(key: String) {
        synchronized(publishLock) {
            pendingJobs.remove(key)?.cancel()
            pending.remove(key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        deduplicationManager.remove(sbn.key)
        app.replyActionCache.remove(sbn.key)
        // 取消该通知待发的去抖发布，避免移除（清空分组）后旧消息又被补发
        cancelPending(sbn.key)
        // 下发移除事件：让 B 端清空该联系人分组，重新计数
        val pkg = sbn.packageName
        // 用首次采集的标题/正文（移除时通话类通知内容可能已变化，避免分组匹配失败）
        val title = capturedTitle.get(sbn.key) ?: NotificationExtractor.title(sbn)
        val text = capturedText.get(sbn.key) ?: NotificationExtractor.text(sbn)
        val contact = capturedContact.get(sbn.key) ?: computeContact(title, text)
        
        // 移除缓存
        capturedTitle.remove(sbn.key)
        capturedText.remove(sbn.key)
        capturedContact.remove(sbn.key)
        scope.launch {
            try {
                if (!app.appFilterRepository.shouldSync(pkg, title, text)) return@launch
                if (!MqttConnection.manager.isConnected) return@launch
                val event = NotificationEvent(
                    type = "remove",
                    id = UUID.randomUUID().toString(),
                    from = app.deviceIdentity.deviceId(),
                    ts = System.currentTimeMillis(),
                    seq = seq.incrementAndGet(),
                    stableKey = sbn.key,
                    pkg = pkg,
                    appName = appLabel(pkg),
                    title = title,
                    text = text,
                    contact = contact
                )
                publishEvent(event)
                Log.d(TAG, "removed event published pkg=$pkg title=$title text=$text")
            } catch (e: Exception) {
                Log.e(TAG, "onNotificationRemoved error", e)
            }
        }
    }

    /** 从标题/正文提取联系人（与接收端 parseParts 保持一致）。 */
    private fun computeContact(title: String, text: String): String {
        val stripped = text.replace(Regex("^\\[\\d+条\\]\\s*"), "")
        val colonIdx = stripped.indexOfFirst { it == ':' || it == '：' }
        return if (colonIdx > 0) stripped.substring(0, colonIdx).trim() else title
    }

    private fun appLabel(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "NotifyListener"
        private const val PUBLISH_DEBOUNCE_MS = 300L
    }
}
