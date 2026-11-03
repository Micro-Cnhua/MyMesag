package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 来电事件（对应项目书 6.3 消息格式）。
 * number 可能为 null（未采用取号方案一/二时），接收端必须处理 null 并显示降级文案。
 */
@Serializable
data class CallEvent(
    val v: Int = 1,
    val type: String = "call",
    val id: String,
    val from: String,
    val ts: Long,
    val seq: Long,
    val state: String,
    val number: String?,
    val contactName: String?,
    val subscriptionId: Int? = null
)
