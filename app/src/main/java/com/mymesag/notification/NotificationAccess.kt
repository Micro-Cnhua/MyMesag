package com.mymesag.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.mymesag.service.NotifyListenerService

/** 通知使用权（Notification Listener）检查与引导。 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean {
        val cn = ComponentName(context, NotifyListenerService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat?.split(":")?.contains(cn.flattenToString()) == true
    }

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 检测是否为鸿蒙系统（HarmonyOS）。
     * 鸿蒙 4.0+ 对通知监听器有更严格的限制。
     */
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
     * 获取鸿蒙系统版本号。
     */
    fun getHarmonyVersion(): String {
        return try {
            val clazz = Class.forName("com.huawei.system.BuildEx")
            val method = clazz.getMethod("getOsVersion")
            method.invoke(clazz)?.toString() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 鸿蒙设备需要额外的设置步骤。
     * 返回需要显示的提示信息。
     */
    fun getHarmonyOSInstructions(): String? {
        if (!isHarmonyOS()) return null
        
        val version = getHarmonyVersion()
        return buildString {
            appendLine("检测到鸿蒙系统 $version")
            appendLine()
            appendLine("鸿蒙 4.0+ 对通知监听器有额外限制，请按以下步骤操作：")
            appendLine()
            appendLine("1. 进入「设置」→「通知和状态栏」→「通知管理」")
            appendLine("2. 找到「通知使用权」或「通知读取权限」")
            appendLine("3. 如果 MyMesag 已开启，先关闭再重新开启")
            appendLine("4. 返回应用信息页，强制停止 MyMesag 后重新打开")
            appendLine()
            appendLine("如果仍不工作，尝试：")
            appendLine("• 重启手机")
            appendLine("• 在「电池优化」中将 MyMesag 设为「不允许优化」")
            appendLine("• 在「自启动管理」中允许 MyMesag")
        }
    }
}
