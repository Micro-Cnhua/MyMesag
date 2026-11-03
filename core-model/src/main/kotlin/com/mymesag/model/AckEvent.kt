package com.mymesag.model

import kotlinx.serialization.Serializable

/** 回执事件（3.3 表 7 ack 主题 / 5.3 表 9 错误码）。 */
@Serializable
data class AckEvent(
    val v: Int = 1,
    val type: String = "ack",
    val id: String,
    val from: String,
    val to: String,
    val ts: Long,
    val replyToId: String,
    val reason: String
)
