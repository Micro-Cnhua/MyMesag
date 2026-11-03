package com.mymesag.model

import kotlinx.serialization.Serializable

/** 加密封装格式（项目书 6.4 + 多设备 recipient）。外层只保留路由与抗重放所需的明文元数据。 */
@Serializable
data class EncryptedEnvelope(
    val v: Int = 1,
    val senderDeviceId: String,
    val recipientDeviceId: String? = null,
    val seq: Long,
    val ts: Long,
    val iv: String,
    val cipher: String
)
