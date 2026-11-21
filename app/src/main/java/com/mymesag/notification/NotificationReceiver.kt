package com.mymesag.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.mymesag.MyMesagApp
import com.mymesag.R
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.data.followUserIdSubscription
import com.mymesag.model.AckEvent
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.MarkReadEvent
import com.mymesag.model.NotificationEvent
import com.mymesag.model.ReplyEvent
import com.mymesag.model.ReplyResult
import com.mymesag.model.SyncDoneEvent
import com.mymesag.model.SyncRequestEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttState
import com.mymesag.network.MqttTopics
import com.mymesag.reply.ReplyConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * B 端通知还原（阶段 3）：
 * 订阅 notify/{userId}/+ → 反序列化 NotificationEvent → 用 NotificationManager 重建通知，
 * 点击跳转对应 App（受限时降级到应用详情页），并带防回环标记（不变量 2）。
 */
class NotificationReceiver(
    private val context: Context,
    private val manager: MqttManager,
    private val deviceIdentity: DeviceIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 按应用分组的消息累积（简单版：同一应用折叠成一条，展开看多条）
    private val groups = mutableMapOf<String, MutableList<String>>()

    // 内联回复待回执：replyId → 通知 id + 原事件，用于把 A 端 ack 反映到通知状态
    private val pendingInlineReplies = ConcurrentHashMap<String, PendingReply>()

    // 断线补全：每个发送端 B 是否仍待补全；收到其 SyncDone 即置 false 停止重试
    private val pendingRecovery = ConcurrentHashMap<String, Boolean>()
    private var retryJob: Job? = null

    fun start() {
        scope.followUserIdSubscription(deviceIdentity, manager, { MqttTopics.notifyWildcard(it) }, TAG)
        // 订阅 syncreq 通道以接收 B 的补全完成信号（SyncDone）
        scope.followUserIdSubscription(deviceIdentity, manager, { MqttTopics.syncReqWildcard(it) }, TAG)
        scope.launch {
            // 等待会话密钥加载完成再处理消息
            val app = context.applicationContext as MyMesagApp
            app.awaitSessionKeys()
            Log.d(TAG, "session keys loaded, starting message processing")

            manager.messages.collect { msg ->
                when {
                    msg.topic.startsWith("notify/") -> handleNotification(msg.payload)
                    msg.topic.startsWith("syncreq/") -> handleSyncDone(msg.payload)
                }
            }
        }
        // 重连触发补全请求 + 重试；再次断线则取消重试
        scope.launch {
            var prevConnected = false
            manager.state.collect { st ->
                val connected = st == MqttState.CONNECTED
                if (connected && !prevConnected) {
                    onReconnected()
                } else if (!connected && prevConnected) {
                    retryJob?.cancel()
                    pendingRecovery.clear()
                }
                prevConnected = connected
            }
        }
        // 内联回复回执：把 A 端 ack 反映到通知状态（成功延时撤下、失败保留内联回复以便重试）
        scope.launch {
            val app = context.applicationContext as MyMesagApp
            val myId = deviceIdentity.deviceId()
            app.replyAckReceiver.acks.collect { ack ->
                if (ack.to != myId) return@collect
                val p = takePending(ack) ?: return@collect
                if (ack.reason == ReplyResult.OK) {
                    postReplyStatus(p.event, p.notifId, p.original, "✓ 回复成功", allowReply = false)
                    scope.launch { delay(3000); dismissNotification(p.notifId, p.event) }
                } else {
                    postReplyStatus(p.event, p.notifId, p.original, "✗ ${reasonText(ack.reason)}", allowReply = true)
                }
            }
        }
    }

    private suspend fun handleNotification(payload: String) {
        val app = context.applicationContext as MyMesagApp
        if (!app.appFilterRepository.syncReceiveEnabled.first()) {
            return
        }
        val envelope = try {
            json.decodeFromString(EncryptedEnvelope.serializer(), payload)
        } catch (e: Exception) {
            Log.e(TAG, "decode envelope error: ${e.message}")
            return
        }
        val myId = deviceIdentity.deviceId()
        // 多设备：只处理发给本机的信封
        if (envelope.recipientDeviceId != null && envelope.recipientDeviceId != myId) {
            return
        }
        val plaintext = CryptoManager.decrypt(envelope) ?: return
        val event = try {
            json.decodeFromString(NotificationEvent.serializer(), plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "decode event error: ${e.message}")
            return
        }
        // 跳过本机自己发布的消息（避免自收回环）
        if (event.from == myId) {
            Log.d(TAG, "skip self message from=${event.from}")
            return
        }
        // 推进该发送端的接收水位（用事件原始 ts，B 时钟域），供下次重连增量补全
        app.receivedNotifyStore.advanceWatermark(event.from, event.ts)
        // 移除事件：清空该联系人分组，重新计数
        if (event.type == "remove") {
            val parsed = NotificationParser.parse(event)
            val key = groupKey(event, parsed)
            groups.remove(key)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(SYNCED_TAG, key.hashCode())

            // 更新分组摘要
            updateGroupSummary(nm, event, parsed)

            // 更新应用角标
            val totalNotifications = groups.values.sumOf { it.size }
            NotificationBadgeHelper.setBadge(context, totalNotifications)

            Log.d(TAG, "removed group key=$key title=${event.title} text=${event.text} badge=$totalNotifications")
            return
        }
        rebuild(event)
    }

    private suspend fun rebuild(event: NotificationEvent) {
        // 持久指纹去重：重放/重复通知不再重复堆叠（进程重启后依然有效）
        val app = context.applicationContext as MyMesagApp
        val fp = fingerprint(event)
        if (app.receivedNotifyStore.contains(fp)) {
            Log.d(TAG, "duplicate notify skipped stableKey=${event.stableKey}")
            return
        }
        app.receivedNotifyStore.addFingerprint(fp)
        ensureChannel()
        val nm = context.getSystemService(NotificationManager::class.java)

        // 使用应用特定的解析器
        val parsed = NotificationParser.parse(event)
        val contact = parsed.contact
        val message = parsed.message
        val key = groupKey(event, parsed)
        val line = message.ifBlank { event.text }

        // 按「应用+联系人」归类累积消息（同类折叠成一条，展开看多条）
        val group = groups.getOrPut(key) { mutableListOf() }
        group.add(line)
        while (group.size > MAX_LINES) group.removeAt(0)
        val lines = group.toList()

        // 构建标题：群聊显示 "群名 · 发言人"，私聊显示 "应用名 · 联系人"
        val baseTitle = if (parsed.isGroup && parsed.groupName != null) {
            "${parsed.groupName} · $contact"
        } else if (contact.isNotBlank()) {
            "${event.appName} · $contact"
        } else {
            event.appName
        }
        // 多机配对时，用发送端的自定义设备标签区分来源
        val titleText = deviceTagPrefix(event) + baseTitle

        // 大图标：本地取源应用图标；源应用未安装则回退 MyMesag 默认图标
        val largeIcon = NotificationIconFactory.sourceIcon(context, event.pkg)
            ?: NotificationIconFactory.defaultIcon(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(titleText)
            .setContentText(if (lines.size > 1) "${lines.size}条新消息 · ${lines.last()}" else lines.last())
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(event))
            .setGroup("mymesag_${event.pkg}") // 原生分组
            .setGroupSummary(false)
        largeIcon?.let { builder.setLargeIcon(it) }

        if (lines.size > 1) {
            // 多条消息：InboxStyle 折叠展示
            val style = NotificationCompat.InboxStyle().setSummaryText("共${lines.size}条新消息")
            lines.forEach { style.addLine(it) }
            builder.setStyle(style)
        } else {
            // 单条消息：BigTextStyle 完整展示，避免长内容被截断/被回复按钮折叠
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.last()))
        }
        val original = if (contact.isNotBlank()) "$contact：$line" else line
        val notifId = key.hashCode()
        if (event.canReply && ReplyConstants.isSupported(event.pkg)) {
            builder.addAction(buildReplyAction(event, original, notifId))
        }
        if (event.canMarkRead) {
            builder.addAction(buildMarkReadAction(event, notifId))
        }
        val notification = builder.build()
        try {
            // 同一应用+联系人用同一 id，实现折叠覆盖
            nm.notify(SYNCED_TAG, notifId, notification)

            // 创建或更新分组摘要通知
            updateGroupSummary(nm, event, parsed)

            // 更新应用角标
            val totalNotifications = groups.values.sumOf { it.size }
            NotificationBadgeHelper.setBadge(context, totalNotifications)

            Log.d(TAG, "rebuilt pkg=${event.pkg} contact=$contact lines=${lines.size} group=${parsed.isGroup} badge=$totalNotifications")
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted", e)
        }
    }

    private fun updateGroupSummary(
        nm: android.app.NotificationManager,
        event: NotificationEvent,
        parsed: NotificationParser.ParsedNotification
    ) {
        val groupKey = "mymesag_${event.pkg}"

        // 统计该分组下的通知数量
        val groupNotificationCount = groups.entries
            .count { it.key.startsWith("${event.pkg}|") }

        if (groupNotificationCount <= 1) {
            // 只有一个通知，不需要摘要
            nm.cancel(SYNCED_TAG, groupKey.hashCode())
            return
        }

        // 创建摘要通知
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle("${event.appName} · ${groupNotificationCount}个对话")
            .setSummaryText("共${groups.values.sumOf { it.size }}条新消息")

        // 显示每个联系人的最新消息
        groups.entries
            .filter { it.key.startsWith("${event.pkg}|") }
            .take(5)
            .forEach { (key, messages) ->
                val contactName = key.substringAfter("|").take(20)
                if (messages.isNotEmpty()) {
                    inboxStyle.addLine("$contactName: ${messages.last().take(30)}")
                }
            }

        val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(event.appName)
            .setContentText("${groupNotificationCount}个对话的新消息")
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setStyle(inboxStyle)

        try {
            nm.notify(SYNCED_TAG, groupKey.hashCode(), summaryBuilder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to post group summary", e)
        }
    }

    private fun groupKey(event: NotificationEvent, parsed: NotificationParser.ParsedNotification): String {
        // 以「应用 + 会话名」为稳定分组键：微信/QQ 等群聊 groupName 与私聊 contact 都等于通知标题
        // （会话名），不随单条正文有无冒号而变化。统一用它可避免同一会话被拆成多组（首条不合并），
        // 也保证移除事件（用首条标题解析）能匹配并清空对应分组（自动重置）。
        val conversation = parsed.groupName ?: parsed.contact
        return "${event.pkg}|$conversation"
    }

    /** 多机配对时，用发送端设备标签作前缀区分来源；单机或无标签则返回空串。 */
    private suspend fun deviceTagPrefix(event: NotificationEvent): String {
        if (event.deviceTag.isBlank()) return ""
        val app = context.applicationContext as MyMesagApp
        val pairedCount = app.pairingManager.pairedDeviceIds().size
        return if (pairedCount > 1) "[${event.deviceTag}] " else ""
    }

    private fun buildContentIntent(event: NotificationEvent): PendingIntent {
        val requestCode = event.stableKey.hashCode()
        // 通话类通知点击时跳回本机拨号器，并尽量把通知里的号码预填进去（ACTION_DIAL，只预填不直接拨出）。
        // 主判据：源通知类别 CATEGORY_CALL（随事件下发，覆盖所有厂商）；包名列表兜底（类别未设置时）。
        val intent = if (event.isCall || isCallApp(event.pkg)) {
            val number = extractPhoneNumber(event)
            Intent(Intent.ACTION_DIAL, number?.let { Uri.parse("tel:$it") })
        } else {
            (context.packageManager.getLaunchIntentForPackage(event.pkg)
                ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${event.pkg}")))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun isCallApp(pkg: String): Boolean = pkg in CALL_APP_PACKAGES

    /** 从通知的联系人/标题/正文里尽力提取电话号码（用于预填拨号器）；提不到返回 null。 */
    private fun extractPhoneNumber(event: NotificationEvent): String? {
        val regex = Regex("\\+?\\d[\\d\\s()\\-]{5,}\\d")
        for (s in listOf(event.contact, event.title, event.text)) {
            if (s.isBlank()) continue
            val candidate = regex.find(s)?.value?.filter { it.isDigit() || it == '+' } ?: continue
            if (candidate.count { it.isDigit() } >= 6) return candidate
        }
        return null
    }

    private fun buildReplyAction(event: NotificationEvent, original: String, notifId: Int): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("输入回复内容").build()
        val intent = Intent(context, InlineReplyReceiver::class.java).apply {
            putExtra(EXTRA_REPLY_TO, event.stableKey)
            putExtra(EXTRA_PKG, event.pkg)
            putExtra(EXTRA_TARGET, event.from)
            putExtra(EXTRA_NOTIF_ID, notifId)
            putExtra(EXTRA_ORIGINAL, original)
            putExtra(EXTRA_EVENT, json.encodeToString(NotificationEvent.serializer(), event))
        }
        // RemoteInput 必须用可变 PendingIntent，系统才能把输入框结果回填进 intent
        val pi = PendingIntent.getBroadcast(
            context, event.stableKey.hashCode() * 31, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_notify, "回复", pi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun buildMarkReadAction(event: NotificationEvent, notifId: Int): NotificationCompat.Action {
        val intent = Intent(context, InlineMarkReadReceiver::class.java).apply {
            putExtra(EXTRA_REPLY_TO, event.stableKey)
            putExtra(EXTRA_PKG, event.pkg)
            putExtra(EXTRA_TARGET, event.from)
            putExtra(EXTRA_NOTIF_ID, notifId)
            putExtra(EXTRA_EVENT, json.encodeToString(NotificationEvent.serializer(), event))
        }
        val pi = PendingIntent.getBroadcast(
            context, event.stableKey.hashCode() * 31 + 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_notify, "标记已读", pi).build()
    }

    /** 通知内联回复入口（InlineReplyReceiver 调用）：直接在通知里输入并远程发送，不跳转应用。 */
    fun onInlineReply(intent: Intent) {
        scope.launch { handleInlineReply(intent) }
    }

    /** 通知内联标记已读入口（InlineMarkReadReceiver 调用）：直接远程标记已读后撤下通知。 */
    fun onInlineMarkRead(intent: Intent) {
        scope.launch { handleInlineMarkRead(intent) }
    }

    private suspend fun handleInlineReply(intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val event = decodeEvent(intent) ?: return
        val replyToId = intent.getStringExtra(EXTRA_REPLY_TO) ?: return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, event.stableKey.hashCode())
        val original = intent.getStringExtra(EXTRA_ORIGINAL) ?: ""

        if (!manager.isConnected) {
            postReplyStatus(event, notifId, original, "✗ 本机未连接 MQTT，无法回复", allowReply = true)
            return
        }
        val userId = deviceIdentity.userId()
        val myId = deviceIdentity.deviceId()
        val replyId = UUID.randomUUID().toString()
        val ev = ReplyEvent(
            id = replyId, from = myId, to = target,
            ts = System.currentTimeMillis(), seq = System.currentTimeMillis(),
            replyToId = replyToId, pkg = pkg, text = text
        )
        val env = CryptoManager.encrypt(json.encodeToString(ReplyEvent.serializer(), ev), myId, target)
        if (env == null) {
            postReplyStatus(event, notifId, original, "✗ 缺少会话密钥，请重新配对后再回复", allowReply = true)
            return
        }
        pendingInlineReplies[replyId] = PendingReply(notifId, event, original)
        postReplyStatus(event, notifId, original, "回复发送中，等待对方回执…", allowReply = false)
        manager.publish(MqttTopics.reply(userId, target), json.encodeToString(EncryptedEnvelope.serializer(), env))
        scope.launch {
            delay(ACK_TIMEOUT_MS)
            val p = pendingInlineReplies.remove(replyId) ?: return@launch
            postReplyStatus(p.event, p.notifId, p.original, "✗ 未收到对方回执，对方可能离线", allowReply = true)
        }
    }

    private suspend fun handleInlineMarkRead(intent: Intent) {
        val event = decodeEvent(intent) ?: return
        val replyToId = intent.getStringExtra(EXTRA_REPLY_TO) ?: return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, event.stableKey.hashCode())
        if (!manager.isConnected) {
            toast("本机未连接 MQTT，无法标记已读")
            return
        }
        val userId = deviceIdentity.userId()
        val myId = deviceIdentity.deviceId()
        val ev = MarkReadEvent(
            id = UUID.randomUUID().toString(), from = myId, to = target,
            ts = System.currentTimeMillis(), replyToId = replyToId, pkg = pkg
        )
        val env = CryptoManager.encrypt(json.encodeToString(MarkReadEvent.serializer(), ev), myId, target)
        if (env == null) {
            toast("缺少会话密钥，请重新配对后再试")
            return
        }
        manager.publish(MqttTopics.markRead(userId, target), json.encodeToString(EncryptedEnvelope.serializer(), env))
        // 按需求：点击后直接撤下该通知，不再跳转应用二次确认
        dismissNotification(notifId, event)
        Log.d(TAG, "inline markread sent replyToId=$replyToId pkg=$pkg")
    }

    private fun decodeEvent(intent: Intent): NotificationEvent? {
        val raw = intent.getStringExtra(EXTRA_EVENT) ?: return null
        return runCatching { json.decodeFromString(NotificationEvent.serializer(), raw) }.getOrNull()
    }

    /** 回复后把通知刷新为状态视图（保留标题/图标/分组）；allowReply=true 时保留内联回复以便重试。 */
    private suspend fun postReplyStatus(
        event: NotificationEvent, notifId: Int, original: String, status: String, allowReply: Boolean
    ) {
        ensureChannel()
        val nm = context.getSystemService(NotificationManager::class.java)
        val parsed = NotificationParser.parse(event)
        val contact = parsed.contact
        val baseTitle = if (parsed.isGroup && parsed.groupName != null) {
            "${parsed.groupName} · $contact"
        } else if (contact.isNotBlank()) {
            "${event.appName} · $contact"
        } else {
            event.appName
        }
        val titleText = deviceTagPrefix(event) + baseTitle
        val largeIcon = NotificationIconFactory.sourceIcon(context, event.pkg)
            ?: NotificationIconFactory.defaultIcon(context)
        val body = if (original.isNotBlank()) "$original\n$status" else status
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(titleText)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(event))
            .setGroup("mymesag_${event.pkg}")
            .setGroupSummary(false)
        largeIcon?.let { builder.setLargeIcon(it) }
        if (allowReply && event.canReply && ReplyConstants.isSupported(event.pkg)) {
            builder.addAction(buildReplyAction(event, original, notifId))
        }
        if (event.canMarkRead) {
            builder.addAction(buildMarkReadAction(event, notifId))
        }
        try {
            nm.notify(SYNCED_TAG, notifId, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "post reply status denied", e)
        }
    }

    private fun dismissNotification(notifId: Int, event: NotificationEvent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(SYNCED_TAG, notifId)
        val parsed = NotificationParser.parse(event)
        groups.remove(groupKey(event, parsed))
        updateGroupSummary(nm, event, parsed)
        NotificationBadgeHelper.setBadge(context, groups.values.sumOf { it.size })
    }

    private fun takePending(ack: AckEvent): PendingReply? {
        if (ack.replyId.isNotEmpty()) return pendingInlineReplies.remove(ack.replyId)
        val key = pendingInlineReplies.entries.firstOrNull {
            it.value.event.stableKey == ack.replyToId && it.value.event.from == ack.from
        }?.key ?: return null
        return pendingInlineReplies.remove(key)
    }

    private fun reasonText(reason: String): String = when (reason) {
        ReplyResult.NOT_FOUND -> "对方未找到回复入口（通知可能已清除或过期）"
        ReplyResult.NO_REPLY_ACTION -> "对方应用没有可用回复动作，或系统拒绝了回复"
        ReplyResult.OFFLINE_TIMEOUT -> "未收到对方回执，对方可能离线"
        ReplyResult.DECRYPT_FAILED -> "对方解密失败，密钥可能不一致，请重新配对"
        ReplyResult.SEQ_REPLAY -> "对方判定为重放消息，已拒绝"
        ReplyResult.NOT_PAIRED -> "双方未配对或配对已失效"
        ReplyResult.UNSUPPORTED_VERSION -> "对方版本不支持该回复"
        else -> "回复失败（$reason）"
    }

    private fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private data class PendingReply(val notifId: Int, val event: NotificationEvent, val original: String)

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "通知同步", NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)
    }

    /** 接收指纹：稳定通知 ID + 内容哈希，用于持久去重。 */
    private fun fingerprint(event: NotificationEvent): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${event.title}\n${event.text}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return event.stableKey + ":" + digest
    }

    /** 重连后向每个配对发送端请求补全，并周期重试直到收到各自 SyncDone 或次数用尽。 */
    private fun onReconnected() {
        retryJob?.cancel()
        pendingRecovery.clear()
        retryJob = scope.launch {
            val app = context.applicationContext as MyMesagApp
            app.awaitSessionKeys()
            if (!app.appFilterRepository.syncReceiveEnabled.first()) return@launch
            val myId = deviceIdentity.deviceId()
            val userId = deviceIdentity.userId()
            val peers = app.pairingManager.pairedDeviceIds()
            if (peers.isEmpty()) return@launch
            peers.forEach { pendingRecovery[it] = true }
            // 首轮立即请求
            peers.forEach { sendSyncRequest(userId, myId, it) }
            // 重试：每 RETRY_INTERVAL_MS 一轮，最多 MAX_RETRY 轮，全部补全即停
            var round = 0
            while (isActive && round < MAX_RETRY && pendingRecovery.values.any { it }) {
                delay(RETRY_INTERVAL_MS)
                round++
                if (!manager.isConnected) break
                peers.forEach { b -> if (pendingRecovery[b] == true) sendSyncRequest(userId, myId, b) }
            }
        }
    }

    private suspend fun sendSyncRequest(userId: String, myId: String, target: String) {
        val app = context.applicationContext as MyMesagApp
        val since = app.receivedNotifyStore.watermark(target)
        val req = SyncRequestEvent(
            id = UUID.randomUUID().toString(),
            from = myId,
            to = target,
            ts = System.currentTimeMillis(),
            sinceTs = since
        )
        val plain = json.encodeToString(SyncRequestEvent.serializer(), req)
        val env = CryptoManager.encrypt(plain, myId, target) ?: return
        manager.publish(MqttTopics.syncReq(userId, target), json.encodeToString(EncryptedEnvelope.serializer(), env))
        Log.d(TAG, "sync request sent to=$target sinceTs=$since")
    }

    private suspend fun handleSyncDone(payload: String) {
        val envelope = runCatching {
            json.decodeFromString(EncryptedEnvelope.serializer(), payload)
        }.getOrNull() ?: return
        val plaintext = CryptoManager.decrypt(envelope) ?: return
        // syncreq 通道双向：A 只处理完成信号；请求缺少 replayed/upToTs，解码为 done 会失败而被忽略
        val done = runCatching {
            json.decodeFromString(SyncDoneEvent.serializer(), plaintext)
        }.getOrNull() ?: return
        if (done.type != "syncdone") return
        val myId = deviceIdentity.deviceId()
        if (done.to != myId) return
        pendingRecovery[done.from] = false
        val app = context.applicationContext as MyMesagApp
        app.receivedNotifyStore.advanceWatermark(done.from, done.upToTs)
        Log.d(TAG, "sync done from=${done.from} replayed=${done.replayed} upToTs=${done.upToTs}")
    }

    companion object {
        /** 本应用重建通知使用的 tag，用作防回环标记（不变量 2）。 */
        const val SYNCED_TAG = "mymesag_synced"
        const val CHANNEL_ID = "synced_notifications"
        private const val TAG = "NotifReceiver"
        private const val MAX_LINES = 10
        private const val RETRY_INTERVAL_MS = 60_000L
        private const val MAX_RETRY = 5
        private const val ACK_TIMEOUT_MS = 15_000L

        // 内联回复/标记已读广播携带的 extras 与 RemoteInput 结果键
        private const val EXTRA_REPLY_TO = "extra_reply_to"
        private const val EXTRA_PKG = "extra_pkg"
        private const val EXTRA_TARGET = "extra_target"
        private const val EXTRA_NOTIF_ID = "extra_notif_id"
        private const val EXTRA_EVENT = "extra_event"
        private const val EXTRA_ORIGINAL = "extra_original"
        private const val KEY_REPLY_TEXT = "inline_reply_text"

        /** 来电/拨号/联系人类应用包名兜底列表（主判据为通知类别 CATEGORY_CALL）：点击跳回本机拨号器并预填号码。 */
        private val CALL_APP_PACKAGES = setOf(
            // AOSP / 小米 / OPPO / vivo
            "com.android.incallui", "com.android.dialer", "com.android.phone",
            "com.android.server.telecom", "com.android.contacts",
            // 谷歌（Pixel 及海外多数机型的 Google Dialer）
            "com.google.android.dialer", "com.google.android.contacts",
            // 三星 OneUI
            "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.samsung.android.app.telephoneui", "com.samsung.android.contacts",
            // 华为 / 荣耀（拨号集成在联系人）
            "com.huawei.contacts", "com.hihonor.contacts", "com.hihonor.incallui"
        )
    }
}