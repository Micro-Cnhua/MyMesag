package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 远程标记已读事件：B 端请求 A 端触发源应用通知里的「标记已读」动作。
 * replyToId 必须为原通知的稳定通知 ID（stableKey），与回复一致。
 */
@Serializable
data class MarkReadEvent(
    val v: Int = 1,
    val type: String = "markread",
    val id: String,
    val from: String,
    val to: String,
    val ts: Long,
    val replyToId: String,
    val pkg: String
)