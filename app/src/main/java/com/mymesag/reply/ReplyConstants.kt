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
}
