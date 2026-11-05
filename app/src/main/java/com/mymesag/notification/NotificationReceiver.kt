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
import com.mymesag.MainActivity
import com.mymesag.MyMesagApp
import com.mymesag.R
import com.mymesag.data.CryptoManager
import com.mymesag.data.DeviceIdentity
import com.mymesag.model.EncryptedEnvelope
import com.mymesag.model.NotificationEvent
import com.mymesag.network.MqttManager
import com.mymesag.network.MqttTopics
import com.mymesag.reply.ReplyConstants
import com.mymesag.ui.MissedCallReplyActivity
import com.mymesag.ui.ReplyActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    private val json = Json { ignoreUnknownKeys = true }

    // 按应用分组的消息累积（简单版：同一应用折叠成一条，展开看多条）
    private val groups = mutableMapOf<String, MutableList<String>>()

    fun start() {
        scope.launch {
            try {
                val userId = deviceIdentity.userId()
                val topic = MqttTopics.notifyWildcard(userId)
                Log.d(TAG, "subscribe $topic")
                manager.subscribe(topic)
            } catch (e: Exception) {
                Log.e(TAG, "subscribe error", e)
            }
        }
        scope.launch {
            // 等待会话密钥加载完成再处理消息
            val app = context.applicationContext as MyMesagApp
            app.awaitSessionKeys()
            Log.d(TAG, "session keys loaded, starting message processing")
            
            manager.messages.collect { msg ->
                if (msg.topic.startsWith("notify/")) {
                    handleNotification(msg.payload)
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

    private fun rebuild(event: NotificationEvent) {
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
        val titleText = if (parsed.isGroup && parsed.groupName != null) {
            "${parsed.groupName} · $contact"
        } else if (contact.isNotBlank()) {
            "${event.appName} · $contact"
        } else {
            event.appName
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(titleText)
            .setContentText(if (lines.size > 1) "${lines.size}条新消息 · ${lines.last()}" else lines.last())
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(event))
            .setGroup("mymesag_${event.pkg}") // 原生分组
            .setGroupSummary(false)

        if (lines.size > 1) {
            // 多条消息：InboxStyle 折叠展示
            val style = NotificationCompat.InboxStyle().setSummaryText("共${lines.size}条新消息")
            lines.forEach { style.addLine(it) }
            builder.setStyle(style)
        } else {
            // 单条消息：BigTextStyle 完整展示，避免长内容被截断/被回复按钮折叠
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.last()))
        }
        if (event.canReply && ReplyConstants.isSupported(event.pkg)) {
            val original = if (contact.isNotBlank()) "$contact：$line" else line
            builder.addAction(buildReplyAction(event, original))
        }
        if (isMissedCall(event) && event.phoneNumber.isNotBlank()) {
            builder.addAction(buildMissedCallReplyAction(event))
        }
        val notification = builder.build()
        try {
            // 同一应用+联系人用同一 id，实现折叠覆盖
            nm.notify(SYNCED_TAG, key.hashCode(), notification)
            
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
        // 使用解析后的联系人和分组信息
        return if (parsed.isGroup && parsed.groupName != null) {
            "${event.pkg}|group|${parsed.groupName}"
        } else {
            "${event.pkg}|${parsed.contact}"
        }
    }

    private fun buildContentIntent(event: NotificationEvent): PendingIntent {
        val requestCode = event.stableKey.hashCode()
        // 通话类通知点击时打开 MyMesag，而不是跳到本机拨号器（避免误记录通话）
        val intent = if (isCallApp(event.pkg)) {
            Intent(context, MainActivity::class.java)
        } else {
            (context.packageManager.getLaunchIntentForPackage(event.pkg)
                ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${event.pkg}")))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun isCallApp(pkg: String): Boolean =
        pkg == "com.android.incallui" || pkg == "com.android.dialer" ||
            pkg == "com.android.phone" || pkg == "com.android.server.telecom" ||
            pkg == "com.huawei.contacts" || pkg == "com.hihonor.contacts" ||
            pkg == "com.hihonor.incallui"

    private fun buildReplyAction(event: NotificationEvent, original: String): NotificationCompat.Action {
        val intent = Intent(context, ReplyActivity::class.java).apply {
            putExtra(ReplyActivity.EXTRA_REPLY_TO, event.stableKey)
            putExtra(ReplyActivity.EXTRA_PKG, event.pkg)
            putExtra(ReplyActivity.EXTRA_TARGET, event.from)
            putExtra(ReplyActivity.EXTRA_APP_NAME, event.appName)
            putExtra(ReplyActivity.EXTRA_ORIGINAL, original)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, event.stableKey.hashCode() * 31, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_notify, "回复", pi).build()
    }

    private fun isMissedCall(event: NotificationEvent): Boolean {
        val content = "${event.title} ${event.text}"
        val lower = content.lowercase()
        val strongMissed = content.contains("未接来电") || content.contains("未接來電") ||
            content.contains("未接听") || content.contains("未接聽") ||
            lower.contains("missed call") || content.contains("不在着信") ||
            content.contains("부재중")
        if (strongMissed) return true
        val dialerish = event.pkg.contains("dialer") || event.pkg.contains("incallui") ||
            event.pkg.contains("telecom") || event.pkg.contains("phone") ||
            event.pkg.contains("contacts")
        val weakMissed = content.contains("未接") || lower.contains("missed")
        return dialerish && weakMissed
    }

    private fun buildMissedCallReplyAction(event: NotificationEvent): NotificationCompat.Action {
        val intent = Intent(context, MissedCallReplyActivity::class.java).apply {
            putExtra(MissedCallReplyActivity.EXTRA_PHONE, event.phoneNumber)
            putExtra(MissedCallReplyActivity.EXTRA_CONTACT, event.contact.ifBlank { event.title })
            putExtra(MissedCallReplyActivity.EXTRA_TARGET, event.from)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, event.stableKey.hashCode() * 37, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_notify, "回复信息", pi).build()
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "通知同步", NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)
    }

    companion object {
        /** 本应用重建通知使用的 tag，用作防回环标记（不变量 2）。 */
        const val SYNCED_TAG = "mymesag_synced"
        const val CHANNEL_ID = "synced_notifications"
        private const val TAG = "NotifReceiver"
        private const val MAX_LINES = 10
    }
}
