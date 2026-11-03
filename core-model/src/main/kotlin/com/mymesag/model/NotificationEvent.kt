package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 普通通知事件（对应项目书 6.1 消息格式）。
 * 第 6 章为消息格式唯一定义处，本类字段与其保持一致。
 */
@Serializable
data class NotificationEvent(
    val v: Int = 1,
    val type: String = "notification",
    val id: String,
    val from: String,
    val ts: Long,
    val seq: Long,
    val stableKey: String,
    val update: Boolean = false,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val contact: String = "",
    val priority: Int = 1,
    val canReply: Boolean = false,
    val phoneNumber: String = ""
)
