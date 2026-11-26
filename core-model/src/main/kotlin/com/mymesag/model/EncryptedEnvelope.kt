package com.mymesag.model

import kotlinx.serialization.Serializable

/** 加密封装 v2：外层只剩版本号与密文，设备标识与序号全部收进密文（见 [SealedPayload]）。 */
@Serializable
data class EncryptedEnvelope(
    val v: Int = 2,
    val iv: String,
    val cipher: String
)