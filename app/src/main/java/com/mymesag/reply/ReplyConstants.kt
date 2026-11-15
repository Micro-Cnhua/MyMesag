package com.mymesag.reply

/** 支持回复的应用（项目书 2.3 / 9.4 表 4）。 */
object ReplyConstants {

    val SUPPORTED_PKGS = setOf(
        "com.tencent.mm",        // 微信
        "com.android.mms",       // 短信（原生）
        "com.huawei.mms",        // 华为短信
        "com.hihonor.mms",       // 荣耀短信
        "com.samsung.mms",       // 三星短信
        "com.tencent.mobileqq",  // QQ
        "com.tencent.tim"        // TIM
    )

    fun isSupported(pkg: String): Boolean = pkg in SUPPORTED_PKGS
        || pkg.endsWith(".mms") || pkg.endsWith(".messaging") || pkg.endsWith(".conversations")

    /** 是否短信类应用（回复成功后自动「标记已读」仅对短信生效）。 */
    fun isSms(pkg: String): Boolean =
        pkg.endsWith(".mms") || pkg.endsWith(".messaging") || pkg.endsWith(".conversations")

    /**
     * 通知快捷动作是否为「标记为已读」（按标题匹配，中英文）。
     * 不用裸 contains("read")，避免误匹配 "Thread" 等含 read 子串的动作。
     */
    fun isMarkReadTitle(title: String?): Boolean {
        val t = title?.trim()?.lowercase() ?: return false
        return t.contains("已读") || t.contains("mark as read") || t.contains("mark read") || t == "read"
    }
}
