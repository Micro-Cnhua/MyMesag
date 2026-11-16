package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 补全完成信号（B→A）：B 重放结束后必发（哪怕 0 条），A 据此停止重试并推进水位。
 * replayed/upToTs 无默认值以便与 SyncRequestEvent 区分。
 */
@Serializable
data class SyncDoneEvent(
    val v: Int = 1,
    val type: String = "syncdone",
    val id: String,
    val from: String,
    val to: String,
    val ts: Long,
    val replayed: Int,
    val upToTs: Long
)