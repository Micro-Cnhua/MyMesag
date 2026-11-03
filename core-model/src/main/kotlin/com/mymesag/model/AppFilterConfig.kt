package com.mymesag.model

import kotlinx.serialization.Serializable

/**
 * 应用过滤配置与模式（对应项目书 8.1 数据模型）。
 */

@Serializable
data class AppFilterConfig(
    val pkg: String,
    val appName: String,
    val enabled: Boolean
)

@Serializable
enum class FilterMode { WHITELIST, BLACKLIST, ALL }

@Serializable
data class SensitiveRule(
    val id: String,
    val pattern: String,
    val enabled: Boolean
)
