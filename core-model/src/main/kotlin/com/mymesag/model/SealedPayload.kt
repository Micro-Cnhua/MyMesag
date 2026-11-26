package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 密文内的路由头。发送方与序号供接收端抗重放，body 是原事件 JSON。
 * 放在密文里，broker 只能看到通道名与一段不可关联的密文。
 */
@Serializable
data class SealedPayload(
    val sender: String,
    val seq: Long,
    val body: String
)