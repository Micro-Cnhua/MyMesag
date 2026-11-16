package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 补发请求（A→B）：接收端重连后，请求发送端重放 sinceTs 之后已发送的通知。
 * sinceTs 为 A 记录的该 B 的接收水位（B 时钟域），无默认值以便与 SyncDoneEvent 区分。
 */
@Serializable
data class SyncRequestEvent(
    val v: Int = 1,
    val type: String = "syncreq",
    val id: String,
    val from: String,
    val to: String,
    val ts: Long,
    val sinceTs: Long
)