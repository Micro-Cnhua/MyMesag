package com.mymesag.notification

import android.content.Context
import android.os.Build

/**
 * 国产 ROM 兼容性处理
 */
object RomCompatibility {
    
    enum class RomType {
        HUAWEI_HARMONY,      // 鸿蒙
        HONOR_MAGIC,         // MagicOS
        XIAOMI_MIUI,         // MIUI/HyperOS
        OPPO_COLOROS,        // ColorOS
        VIVO_ORIGINOS,       // OriginOS
        SAMSUNG_ONEUI,       // OneUI
        STOCK_ANDROID,       // 原生安卓
        OTHER
    }
    
    fun detectRomType(): RomType {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        
        return when {
            // 鸿蒙检测
            isHarmonyOS() -> RomType.HUAWEI_HARMONY
            // 荣耀 MagicOS
            brand.contains("honor") -> RomType.HONOR_MAGIC
            // 小米 MIUI/HyperOS
            brand.contains("xiaomi") || brand.contains("redmi") -> RomType.XIAOMI_MIUI
            // OPPO ColorOS
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") -> RomType.OPPO_COLOROS
            // vivo OriginOS
            brand.contains("vivo") || brand.contains("iqoo") -> RomType.VIVO_ORIGINOS
            // 三星
            brand.contains("samsung") -> RomType.SAMSUNG_ONEUI
            // 华为（非鸿蒙）
            brand.contains("huawei") -> RomType.HUAWEI_HARMONY
            else -> RomType.STOCK_ANDROID
        }
    }
    
    fun isHarmonyOS(): Boolean {
        return try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsBrand")
            method.invoke(clazz) == "harmony"
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取 ROM 特定的保活建议
     */
    fun getKeepAliveInstructions(context: Context): String {
        return when (detectRomType()) {
            RomType.HUAWEI_HARMONY -> """
                鸿蒙系统保活设置：
                1. 设置 → 应用和服务 → 启动管理 → MyMesag → 手动管理
                   ✓ 允许自启动
                   ✓ 允许关联启动
                   ✓ 允许后台活动
                2. 设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接
                3. 设置 → 通知和状态栏 → 通知管理 → 通知使用权 → MyMesag
            """.trimIndent()
            
            RomType.HONOR_MAGIC -> """
                MagicOS 保活设置：
                1. 设置 → 应用和服务 → 启动管理 → MyMesag → 手动管理
                   ✓ 允许自启动
                   ✓ 允许关联启动
                   ✓ 允许后台活动
                2. 设置 → 电池 → 更多电池设置 → 休眠时始终保持网络连接
                3. 设置 → 通知 → 通知管理 → 通知使用权 → MyMesag
            """.trimIndent()
            
            RomType.XIAOMI_MIUI -> """
                MIUI/HyperOS 保活设置：
                1. 设置 → 应用设置 → 应用管理 → MyMesag → 省电策略 → 无限制
                2. 设置 → 应用设置 → 权限管理 → MyMesag → 自启动 → 开启
                3. 设置 → 通知与状态栏 → 通知管理 → 通知使用权 → MyMesag
                4. 安全中心 → 权限管理 → 自启动管理 → MyMesag → 开启
            """.trimIndent()
            
            RomType.OPPO_COLOROS -> """
                ColorOS 保活设置：
                1. 设置 → 电池 → 更多设置 → 休眠时保持网络连接
                2. 设置 → 应用管理 → MyMesag → 允许后台运行
                3. 设置 → 权限与隐私 → 通知使用权 → MyMesag
                4. 手机管家 → 权限隐私 → 自启动管理 → MyMesag → 允许
            """.trimIndent()
            
            RomType.VIVO_ORIGINOS -> """
                OriginOS 保活设置：
                1. 设置 → 电池 → 后台耗电管理 → MyMesag → 允许后台高耗电
                2. 设置 → 应用与权限 → 应用管理 → MyMesag → 权限 → 自启动
                3. 设置 → 通知与状态栏 → 通知管理 → 通知使用权 → MyMesag
                4. i管家 → 应用管理 → 权限管理 → 自启动管理 → MyMesag
            """.trimIndent()
            
            else -> """
                通用保活设置：
                1. 设置 → 电池 → 电池优化 → MyMesag → 不优化
                2. 设置 → 应用 → MyMesag → 电池 → 不限制
                3. 设置 → 通知 → 通知使用权 → MyMesag
            """.trimIndent()
        }
    }
    
    /**
     * 是否需要额外的权限检查
     */
    fun needsExtraPermissions(): List<String> {
        return when (detectRomType()) {
            RomType.XIAOMI_MIUI -> listOf(
                "android.permission.REQUEST_INSTALL_PACKAGES" // MIUI 需要
            )
            else -> emptyList()
        }
    }
    
    /**
     * 获取 ROM 特定的注意事项
     */
    fun getRomSpecificWarnings(): String? {
        return when (detectRomType()) {
            RomType.HUAWEI_HARMONY -> {
                val version = getHarmonyVersion()
                if (version >= "4.0") {
                    "⚠️ 鸿蒙 $version 对通知监听器有额外限制，如遇问题请重新绑定通知使用权"
                } else null
            }
            RomType.XIAOMI_MIUI -> {
                "⚠️ MIUI 可能会在后台杀死应用，建议锁定任务（最近任务界面下拉应用）"
            }
            RomType.OPPO_COLOROS -> {
                "⚠️ ColorOS 的后台管理较严格，建议将应用添加到自启动白名单"
            }
            else -> null
        }
    }
    
    private fun getHarmonyVersion(): String {
        return try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsVersion")
            method.invoke(clazz)?.toString() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
