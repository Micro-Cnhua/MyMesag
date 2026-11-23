package com.mymesag.model

import kotlinx.serialization.Serializable

/** 二维码配对载荷（项目书 11.1）。二维码中不得包含任何长期私钥。 */
@Serializable
data class PairingPayload(
    val v: Int = 1,
    /** 已有组的路由令牌；未配对设备留空，由配对协商确定。 */
    val groupToken: String = "",
    val deviceId: String,
    val deviceName: String,
    val pubKey: String,
    val fingerprint: String,
    val broker: String
)