package com.mymesag.notification

import android.util.Log
import com.mymesag.model.NotificationEvent

/**
 * 应用特定的通知解析器
 * 针对不同应用的通知格式优化联系人和内容提取
 */
object NotificationParser {
    
    private const val TAG = "NotificationParser"
    
    data class ParsedNotification(
        val contact: String,
        val message: String,
        val isGroup: Boolean = false,
        val groupName: String? = null
    )
    
    /**
     * 解析通知，根据应用类型使用不同的解析策略
     */
    fun parse(event: NotificationEvent): ParsedNotification {
        return when (event.pkg) {
            "com.tencent.mm" -> parseWeChat(event)
            "com.tencent.mobileqq" -> parseQQ(event)
            "com.tencent.tim" -> parseTIM(event)
            "com.google.android.apps.messaging" -> parseGoogleMessages(event)
            "com.whatsapp" -> parseWhatsApp(event)
            "org.telegram.messenger" -> parseTelegram(event)
            else -> {
                val isSms = event.pkg.endsWith(".mms")
                    || event.pkg.endsWith(".messaging")
                    || event.pkg.endsWith(".conversations")
                if (isSms) parseSMS(event) else parseGeneric(event)
            }
        }
    }
    
    /**
     * 微信通知解析
     * 格式：
     * - 私聊：标题=联系人，正文=消息
     * - 群聊：标题=群名，正文="联系人: 消息"
     */
    private fun parseWeChat(event: NotificationEvent): ParsedNotification {
        val title = event.title
        val text = event.text
        
        // 如果是群聊，正文格式通常是 "联系人名: 消息内容"
        val colonIdx = text.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx > 0 && colonIdx < 20) { // 联系人名通常不会太长
            val contact = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            return ParsedNotification(
                contact = contact,
                message = message,
                isGroup = true,
                groupName = title
            )
        }
        
        // 私聊
        return ParsedNotification(
            contact = title,
            message = text,
            isGroup = false
        )
    }
    
    /**
     * QQ 通知解析
     * 格式类似微信
     */
    private fun parseQQ(event: NotificationEvent): ParsedNotification {
        val title = event.title
        val text = event.text
        
        // QQ 群聊格式
        val colonIdx = text.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx > 0 && colonIdx < 20) {
            val contact = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            return ParsedNotification(
                contact = contact,
                message = message,
                isGroup = true,
                groupName = title
            )
        }
        
        return ParsedNotification(
            contact = title,
            message = text,
            isGroup = false
        )
    }
    
    /**
     * TIM 通知解析
     * 与 QQ 类似
     */
    private fun parseTIM(event: NotificationEvent): ParsedNotification {
        return parseQQ(event) // TIM 格式与 QQ 相同
    }
    
    /**
     * 短信通知解析
     * 格式：标题可能是号码或联系人，正文是内容
     */
    private fun parseSMS(event: NotificationEvent): ParsedNotification {
        val title = event.title
        val text = event.text
        
        // 尝试从标题提取联系人
        val contact = when {
            title.isBlank() -> "未知号码"
            title.matches(Regex("\\+?\\d+")) -> title // 纯数字，是号码
            else -> title // 联系人名称
        }
        
        return ParsedNotification(
            contact = contact,
            message = text,
            isGroup = false
        )
    }
    
    /**
     * Google Messages 解析
     */
    private fun parseGoogleMessages(event: NotificationEvent): ParsedNotification {
        return parseSMS(event) // 格式类似短信
    }
    
    /**
     * WhatsApp 解析
     * 格式：
     * - 私聊：标题=联系人，正文=消息
     * - 群聊：标题=群名，正文="联系人: 消息"
     */
    private fun parseWhatsApp(event: NotificationEvent): ParsedNotification {
        val title = event.title
        val text = event.text
        
        val colonIdx = text.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx > 0 && colonIdx < 20) {
            val contact = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            return ParsedNotification(
                contact = contact,
                message = message,
                isGroup = true,
                groupName = title
            )
        }
        
        return ParsedNotification(
            contact = title,
            message = text,
            isGroup = false
        )
    }
    
    /**
     * Telegram 解析
     */
    private fun parseTelegram(event: NotificationEvent): ParsedNotification {
        val title = event.title
        val text = event.text
        
        // Telegram 群聊格式
        val colonIdx = text.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx > 0 && colonIdx < 20) {
            val contact = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            return ParsedNotification(
                contact = contact,
                message = message,
                isGroup = true,
                groupName = title
            )
        }
        
        return ParsedNotification(
            contact = title,
            message = text,
            isGroup = false
        )
    }
    
    /**
     * 通用解析器
     * 尝试从标题和正文中提取联系人
     */
    private fun parseGeneric(event: NotificationEvent): ParsedNotification {
        val title = event.title
        val text = event.text
        
        // 尝试从正文提取联系人（格式："联系人名: 消息"）
        val colonIdx = text.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx > 0 && colonIdx < 20) {
            val contact = text.substring(0, colonIdx).trim()
            val message = text.substring(colonIdx + 1).trim()
            return ParsedNotification(
                contact = contact,
                message = message,
                isGroup = false
            )
        }
        
        // 使用标题作为联系人
        return ParsedNotification(
            contact = title.ifBlank { event.appName },
            message = text,
            isGroup = false
        )
    }
}
