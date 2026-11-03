package com.mymesag.notification

import android.content.Context
import android.content.Intent
import android.util.Log
import com.mymesag.notification.RomCompatibility.RomType

/**
 * 通知徽章（角标）支持
 * 不同 ROM 使用不同的方式显示通知计数
 */
object NotificationBadgeHelper {
    
    private const val TAG = "NotificationBadge"
    
    /**
     * 设置应用角标数量
     */
    fun setBadge(context: Context, count: Int) {
        val romType = RomCompatibility.detectRomType()
        
        try {
            when (romType) {
                RomType.HUAWEI_HARMONY -> setHuaweiBadge(context, count)
                RomType.HONOR_MAGIC -> setHonorBadge(context, count)
                RomType.XIAOMI_MIUI -> setXiaomiBadge(context, count)
                RomType.OPPO_COLOROS -> setOppoBadge(context, count)
                RomType.VIVO_ORIGINOS -> setVivoBadge(context, count)
                RomType.SAMSUNG_ONEUI -> setSamsungBadge(context, count)
                else -> setGenericBadge(context, count)
            }
            Log.d(TAG, "Badge set to $count for $romType")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set badge for $romType", e)
        }
    }
    
    /**
     * 清除角标
     */
    fun clearBadge(context: Context) {
        setBadge(context, 0)
    }
    
    /**
     * 华为/鸿蒙角标
     */
    private fun setHuaweiBadge(context: Context, count: Int) {
        val bundle = android.os.Bundle().apply {
            putString("package", context.packageName)
            putString("class", "${context.packageName}.MainActivity")
            putInt("badgenumber", count)
        }
        context.contentResolver.call(
            android.net.Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
            "change_badge",
            null,
            bundle
        )
    }
    
    /**
     * 荣耀角标
     */
    private fun setHonorBadge(context: Context, count: Int) {
        val intent = Intent("com.hihonor.android.launcher.action.BADGE_COUNT_UPDATE").apply {
            putExtra("com.hihonor.android.launcher.badge.BADGE_COUNT", count)
            putExtra("com.hihonor.android.launcher.badge.PACKAGE_NAME", context.packageName)
            putExtra("com.hihonor.android.launcher.badge.CLASS_NAME", "${context.packageName}.MainActivity")
        }
        context.sendBroadcast(intent)
    }
    
    /**
     * 小米角标
     */
    private fun setXiaomiBadge(context: Context, count: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        // 小米使用 NotificationChannel 的 setShowBadge 和通知的 number 属性
        val notification = android.app.Notification.Builder(context, "synced_notifications")
            .setContentTitle("MyMesag")
            .setContentText("$count 条未读消息")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setNumber(count)
            .build()
        
        // 发送一个静默通知来更新角标
        notificationManager.notify(0, notification)
        notificationManager.cancel(0)
    }
    
    /**
     * OPPO角标
     */
    private fun setOppoBadge(context: Context, count: Int) {
        try {
            val intent = Intent("com.oppo.unsettledevent").apply {
                putExtra("com.oppo.unsettledevent.badgeCount", count)
                putExtra("com.oppo.unsettledevent.packageName", context.packageName)
                putExtra("com.oppo.unsettledevent.className", "${context.packageName}.MainActivity")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "OPPO badge not supported", e)
        }
    }
    
    /**
     * vivo角标
     */
    private fun setVivoBadge(context: Context, count: Int) {
        try {
            val intent = Intent("launcher.action.CHANGE_BADGE").apply {
                putExtra("launcher.intent.extra.BADGE_COUNT", count)
                putExtra("launcher.intent.extra.PACKAGE_NAME", context.packageName)
                putExtra("launcher.intent.extra.CLASS_NAME", "${context.packageName}.MainActivity")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "vivo badge not supported", e)
        }
    }
    
    /**
     * 三星角标
     */
    private fun setSamsungBadge(context: Context, count: Int) {
        val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
            putExtra("badge_count", count)
            putExtra("badge_count_package_name", context.packageName)
            putExtra("badge_count_class_name", "${context.packageName}.MainActivity")
        }
        context.sendBroadcast(intent)
    }
    
    /**
     * 通用角标（使用 ShortcutBadger 库或原生 API）
     */
    private fun setGenericBadge(context: Context, count: Int) {
        // 原生 Android 不支持角标，这里不做任何操作
        // 可以考虑使用 ShortcutBadger 库来支持更多设备
        Log.d(TAG, "Generic badge not supported on this ROM")
    }
}
