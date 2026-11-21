package com.mymesag.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mymesag.MyMesagApp

/** 通知内联标记已读：点击后直接远程标记已读并撤下通知，不跳转应用二次确认。 */
class InlineMarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as MyMesagApp).notificationReceiver.onInlineMarkRead(intent)
    }
}