package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 回复事件（对应项目书 6.2 消息格式）。
 * replyToId 必须为原通知的稳定通知 ID（stableKey），不得使用随机 UUID。
 */
@Serializable
data class ReplyEvent(
    val v: Int = 1,
    val type: String = "reply",
    val id: String,
    val from: String,
    val to: String,
    val ts: Long,
    val seq: Long,
    val replyToId: String,
    val pkg: String,
    val text: String
)
