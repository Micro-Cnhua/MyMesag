package com.mymesag.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mymesag.MyMesagApp

/** 通知内联回复：把 RemoteInput 输入结果交给 NotificationReceiver 直接远程发送，不跳转应用。 */
class InlineReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as MyMesagApp).notificationReceiver.onInlineReply(intent)
    }
}