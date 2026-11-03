package com.mymesag.notification

import android.app.Notification
import android.service.notification.StatusBarNotification

/** 从 StatusBarNotification 提取标题 / 正文 / 是否可回复（项目书 5.1 / 9.1）。 */
object NotificationExtractor {

    fun title(sbn: StatusBarNotification): String =
        sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    fun text(sbn: StatusBarNotification): String {
        val e = sbn.notification.extras
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it?.toString().orEmpty() }
        return listOfNotNull(
            e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            e.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            lines,
            e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        ).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    fun hasRemoteInput(sbn: StatusBarNotification): Boolean =
        sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
}
